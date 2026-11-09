package com.sentinel.rag.suite;

import com.sentinel.rag.entity.TestCase;
import com.sentinel.rag.entity.TestSuite;
import com.sentinel.rag.repository.TestCaseRepository;
import com.sentinel.rag.repository.TestSuiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoldenSuiteLoaderTest {

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    private GoldenSuiteLoader loader;

    @BeforeEach
    void setUp() {
        loader = new GoldenSuiteLoader(testSuiteRepository, testCaseRepository);
    }

    @Test
    void loadSuites_successfullyParsesAndUpsertsFromClasspath() {
        UUID suiteId = UUID.fromString("5fb896fe-9a4d-4a06-89d5-1d93dbdede73");
        TestSuite mockSuite = new TestSuite();
        mockSuite.setSuiteId(suiteId);
        mockSuite.setName("rag_core_suite");

        when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.empty());
        when(testSuiteRepository.findByName("rag_core_suite")).thenReturn(Optional.empty());
        when(testSuiteRepository.save(any(TestSuite.class))).thenAnswer(invocation -> {
            TestSuite s = invocation.getArgument(0);
            if (s.getSuiteId() == null) {
                s.setSuiteId(suiteId);
            }
            return s;
        });

        when(testCaseRepository.findBySuite_SuiteIdAndQuery(eq(suiteId), anyString()))
                .thenReturn(Optional.empty());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<TestSuite> loaded = loader.loadSuites();

        assertFalse(loaded.isEmpty(), "Should have loaded at least one suite from classpath");
        verify(testSuiteRepository, atLeastOnce()).save(any(TestSuite.class));

        ArgumentCaptor<TestCase> caseCaptor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository, atLeast(5)).save(caseCaptor.capture());

        List<TestCase> savedCases = caseCaptor.getAllValues();
        boolean hasRefusal = savedCases.stream().anyMatch(TestCase::isExpectsRefusal);
        boolean hasNonRefusal = savedCases.stream().anyMatch(tc -> !tc.isExpectsRefusal());

        assertTrue(hasRefusal, "Should have loaded cases with expects_refusal = true");
        assertTrue(hasNonRefusal, "Should have loaded normal non-refusal cases");
    }

    @Test
    void loadSuites_whenSuiteAlreadyExists_updatesManagedEntity() {
        UUID suiteId = UUID.fromString("5fb896fe-9a4d-4a06-89d5-1d93dbdede73");
        TestSuite existingSuite = new TestSuite();
        existingSuite.setSuiteId(suiteId);
        existingSuite.setName("rag_core_suite");
        existingSuite.setDescription("Old description");

        when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(existingSuite));
        when(testSuiteRepository.save(any(TestSuite.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TestCase existingCase = new TestCase();
        existingCase.setTestId(UUID.randomUUID());
        existingCase.setSuite(existingSuite);
        existingCase.setQuery("What is the default port for Redis?");

        when(testCaseRepository.findBySuite_SuiteIdAndQuery(eq(suiteId), anyString()))
                .thenReturn(Optional.of(existingCase));
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<TestSuite> loaded = loader.loadSuites();

        assertEquals(1, loaded.size());
        TestSuite result = loaded.get(0);
        assertEquals(suiteId, result.getSuiteId());
        // Verify description was updated on existing entity
        assertEquals("Core golden benchmark test suite for Sentinel-RAG CI gate evaluation", result.getDescription());
        verify(testSuiteRepository, atLeastOnce()).save(existingSuite);
        verify(testCaseRepository, atLeast(5)).save(existingCase);
    }
}
