# bx-debugger Project Instructions

This project is a DAP compliant debugger for BoxLang, a dynamic JVM language.

This project will primarily be written in Java in order to provide the BoxLang runtime with a debugger.

BoxLang is a scripting language that runs on the JVM, designed to be simple and easy to use. It is particularly suited for scripting tasks and rapid application development.

Major priorities:

- Comprehensive and thorough tests
- Good quality documentation
- Peformance and efficiency
- Maintainability and readability of code

When you asked for help, follow these instructions:

- When you respond do not over explain. Share relevant information but do not go into too much detail unless asked.
- When beginning to implemenat a feature, first write a test that describes the feature.
- When discussing a feature, do not assume too much, ask questions to clarify the requirements.
- Make sure to have good separation of concerns in your code. We do not want to put too much in a single file or class.


This project relies heavily on lsp4j to implement the DAP protocol. Refer to the documentation whenever you need to.

Here are some useful links:
https://github.com/eclipse-lsp4j/lsp4j/blob/main/documentation/README.md
https://github.com/eclipse-lsp4j/lsp4j/blob/main/documentation/jsonrpc.md
https://microsoft.github.io/debug-adapter-protocol/specification