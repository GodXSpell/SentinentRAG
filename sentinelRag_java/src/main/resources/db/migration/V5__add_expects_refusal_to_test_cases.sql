-- V5__add_expects_refusal_to_test_cases.sql
-- Adds expects_refusal flag to test_cases per Plan §1.3 and §3.1.
-- Defaults to FALSE for backwards compatibility with existing test cases.

ALTER TABLE test_cases ADD COLUMN expects_refusal BOOLEAN NOT NULL DEFAULT FALSE;

