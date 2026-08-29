---
name: Android build environment
description: Environment constraint to check before compiling imported Android projects.
---

Imported Android projects can have Gradle and a JDK available while still lacking an Android SDK location. The workspace package registry may not expose an installable Android SDK package.

**Why:** A Gradle compile cannot resolve Android dependencies or tasks until `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `local.properties` points at a valid SDK.

**How to apply:** Check the JDK and SDK separately before declaring an Android build verified; if the SDK is unavailable through the supported installer, report the compile limitation and run source, XML, Python, and diff checks instead.