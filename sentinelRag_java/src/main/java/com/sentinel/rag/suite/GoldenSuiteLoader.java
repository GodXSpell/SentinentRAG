package com.sentinel.rag.suite;

import com.sentinel.rag.entity.TestCase;
import com.sentinel.rag.entity.TestSuite;
import com.sentinel.rag.repository.TestCaseRepository;
import com.sentinel.rag.repository.TestSuiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * Loads golden evaluation test suites from classpath resources (golden/*.yaml)
 * into the database (test_suites and test_cases tables) per Plan §3.1.
 * Supports idempotent upsert: re-running does not produce duplicate suites or cases.
 */
@Service
public class GoldenSuiteLoader {

    private static final Logger log = LoggerFactory.getLogger(GoldenSuiteLoader.class);

    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseRepository testCaseRepository;

    public GoldenSuiteLoader(TestSuiteRepository testSuiteRepository,
                             TestCaseRepository testCaseRepository) {
        this.testSuiteRepository = Objects.requireNonNull(testSuiteRepository);
        this.testCaseRepository = Objects.requireNonNull(testCaseRepository);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        try {
            loadSuites();
        } catch (Exception e) {
            log.warn("Could not load golden suites on startup (database may be offline or unmigrated): {}", e.getMessage());
        }
    }

    @Transactional
    public List<TestSuite> loadSuites() {
        List<TestSuite> loadedSuites = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] yamlResources = resolver.getResources("classpath*:golden/*.yaml");
            Resource[] ymlResources = resolver.getResources("classpath*:golden/*.yml");

            List<Resource> allResources = new ArrayList<>();
            allResources.addAll(Arrays.asList(yamlResources));
            allResources.addAll(Arrays.asList(ymlResources));

            if (allResources.isEmpty()) {
                log.info("No golden test suites found under classpath:golden/");
                return loadedSuites;
            }

            Yaml yaml = new Yaml();
            for (Resource resource : allResources) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> data = yaml.load(is);
                    if (data == null) continue;

                    TestSuite suite = upsertSuite(data);
                    upsertCases(suite, data);
                    loadedSuites.add(suite);
                    log.info("Loaded golden suite '{}' with ID {}", suite.getName(), suite.getSuiteId());
                } catch (Exception e) {
                    log.error("Failed to parse golden suite resource {}: {}", resource.getFilename(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error scanning golden suite resources: {}", e.getMessage(), e);
        }

        return loadedSuites;
    }

    @SuppressWarnings("unchecked")
    private TestSuite upsertSuite(Map<String, Object> data) {
        String name = (String) data.getOrDefault("name", "unnamed-suite");
        String description = (String) data.getOrDefault("description", "");
        String rawSuiteId = (String) data.get("suite_id");

        Optional<TestSuite> existing = testSuiteRepository.findByName(name);
        TestSuite suite;
        if (existing.isPresent()) {
            suite = existing.get();
            suite.setDescription(description);
        } else {
            suite = new TestSuite();
            suite.setName(name);
            suite.setDescription(description);
            if (rawSuiteId != null) {
                try {
                    suite.setSuiteId(UUID.fromString(rawSuiteId));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return testSuiteRepository.save(suite);
    }

    @SuppressWarnings("unchecked")
    private void upsertCases(TestSuite suite, Map<String, Object> data) {
        Object rawCases = data.get("cases");
        if (!(rawCases instanceof List<?> caseList)) {
            return;
        }

        for (Object item : caseList) {
            if (!(item instanceof Map<?, ?> caseMap)) continue;

            String query = (String) caseMap.get("query");
            String groundTruth = (String) caseMap.get("expected_ground_truth");
            if (query == null || groundTruth == null) continue;

            Boolean expectsRefusal = Boolean.TRUE.equals(caseMap.get("expects_refusal"));
            List<String> tags = Collections.emptyList();
            if (caseMap.get("tags") instanceof List<?> rawTags) {
                tags = rawTags.stream().map(Object::toString).toList();
            }

            Optional<TestCase> existingCase = testCaseRepository
                    .findBySuite_SuiteIdAndQuery(suite.getSuiteId(), query);

            TestCase tc = existingCase.orElseGet(TestCase::new);
            tc.setSuite(suite);
            tc.setQuery(query);
            tc.setExpectedGroundTruth(groundTruth);
            tc.setExpectsRefusal(expectsRefusal);
            tc.setTags(tags);

            testCaseRepository.save(tc);
        }
    }
}

