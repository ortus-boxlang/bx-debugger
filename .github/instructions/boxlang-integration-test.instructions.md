---
applyTo: "**/boxlangIntegration/**"
---

Each Java test file in the `src\test\java\ortus\boxlang\moduleslug\boxlangIntegration` directory should have a corresponding boxlang file in the same directory.

For example, if you have a test file named `StackInformationTest.java`, you should create a boxlang file named `StackInformationTest.bxs` in the same directory.

The boxlang file should contain the BoxLang code that corresponds to the Java test file. This allows you to run the tests in BoxLang and verify that the BoxLang code behaves as expected.

When you implement a test you should:

- Use `[FullDebugSessionTest](../../src/test/java/ortus/boxlang/moduleslug/FullDebugSessionTest.java)` as an example of how to structure your test. DO NOT MODIFY THIS FILE.
- Create a new Java test file in the `boxlangIntegration` directory.