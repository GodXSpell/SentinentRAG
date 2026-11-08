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
import org.springframework.transaction.annotation.Propagation;
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

    /**
     * Startup listener without outer @Transactional boundary so that an individual
     * resource failure does not poison the application event delivery.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            loadSuites();
        } catch (Exception e) {
            log.warn("Could not load golden suites on startup (database may be offline or unmigrated): {}", e.getMessage());
        }
    }

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

                    TestSuite suite = loadSingleSuite(data);
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

    /**
     * Atomic transaction per suite file to prevent cross-suite transaction poisoning.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TestSuite loadSingleSuite(Map<String, Object> data) {
        TestSuite suite = upsertSuite(data);
        upsertCases(suite, data);
        return suite;
    }

    /**
     * Load-first upsert pattern:
     * 1. Check findById(id), fallback to findByName(name).
     * 2. If present, copy updated fields onto managed entity.
     * 3. If absent, create new entity with assigned id and persist.
     */
    @Transactional
    public TestSuite upsertSuite(Map<String, Object> data) {
        String name = (String) data.getOrDefault("name", "unnamed-suite");
        String description = (String) data.getOrDefault("description", "");
        String rawSuiteId = (String) data.get("suite_id");

        UUID suiteId = null;
        if (rawSuiteId != null) {
            try {
                suiteId = UUID.fromString(rawSuiteId);
            } catch (IllegalArgumentException ignored) {
            }
        }

        // 1. Load first by suite_id, fallback to name
        Optional<TestSuite> existing = Optional.empty();
        if (suiteId != null) {
            existing = testSuiteRepository.findById(suiteId);
        }
        if (existing.isEmpty() && name != null) {
            existing = testSuiteRepository.findByName(name);
        }

        TestSuite suite;
        if (existing.isPresent()) {
            // 2. Existing: update fields on the managed entity
            suite = existing.get();
            suite.setName(name);
            suite.setDescription(description);
        } else {
            // 3. Absent: create new TestSuite with assigned id and persist
            suite = new TestSuite();
            suite.setSuiteId(suiteId != null ? suiteId : UUID.randomUUID());
            suite.setName(name);
            suite.setDescription(description);
        }
        return testSuiteRepository.save(suite);
    }

    @Transactional
    public void upsertCases(TestSuite suite, Map<String, Object> data) {
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

            TestCase tc;
            if (existingCase.isPresent()) {
                tc = existingCase.get();
            } else {
                tc = new TestCase();
                tc.setTestId(UUID.randomUUID());
            }
            tc.setSuite(suite);
            tc.setQuery(query);
            tc.setExpectedGroundTruth(groundTruth);
            tc.setExpectsRefusal(expectsRefusal);
            tc.setTags(tags);

            testCaseRepository.save(tc);
        }
    }
}
