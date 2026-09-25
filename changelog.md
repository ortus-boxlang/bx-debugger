# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

* * *

## [Unreleased]

## [1.2.0] - 2026-09-25

- Improve variable dumps in REPL
- Bump BoxLang version to 1.17.0
- BLIDE-328 Fix CFC stack frames
- BLIDE-329 stabilize stop/resume behavior
- BLIDE-330 Improve breakpoint handling
- BLIDE-331 Harden session lifecycle
- BLIDE-332 Add query and paged variable inspection

## [1.1.1] - 2026-03-19

## [1.0.0] - 2026-03-04

Added

- DAP disconnect request handling with terminate/detach/restart semantics
- Basic evaluate support (REPL string literals; hover/watch gated on pause)

Changed

- Continue now resumes all threads and reports allThreadsContinued=true

Fixed

- Stabilized output-related tests with polling and added JDI launch retries

[unreleased]: https://github.com/ortus-boxlang/bx-debugger/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/ortus-boxlang/bx-debugger/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/ortus-boxlang/bx-debugger/compare/v1.0.0...v1.1.1
[1.0.0]: https://github.com/ortus-boxlang/bx-debugger/compare/ae0ec3267d61aedac227a96d07f1e6eebeab58e6...v1.0.0
