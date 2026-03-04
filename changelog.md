# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

* * *

## [Unreleased]

## [1.0.0] - 2026-03-04

Added

- DAP disconnect request handling with terminate/detach/restart semantics
- Basic evaluate support (REPL string literals; hover/watch gated on pause)

Changed

- Continue now resumes all threads and reports allThreadsContinued=true

Fixed

- Stabilized output-related tests with polling and added JDI launch retries

[unreleased]: https://github.com/ortus-boxlang/bx-debugger/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/ortus-boxlang/bx-debugger/compare/ae0ec3267d61aedac227a96d07f1e6eebeab58e6...v1.0.0
