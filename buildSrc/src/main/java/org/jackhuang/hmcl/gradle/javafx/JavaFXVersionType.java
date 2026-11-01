// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.gradle.javafx;

import org.jetbrains.annotations.NotNullByDefault;

/// JavaFX dependency channels and their minimum supported Java versions.
@NotNullByDefault
public enum JavaFXVersionType {
    /// JavaFX dependencies compatible with Java 17 and newer.
    CLASSIC("classic", 17),
    /// JavaFX 27 dependencies, which require Java 25 or newer.
    MODERN("modern", 25);

    /// Key used for this channel in the dependency manifest.
    private final String name;
    /// Minimum Java feature version required by this channel.
    private final int javaVersion;

    /// Creates a channel with its manifest key and minimum Java version.
    JavaFXVersionType(String name, int javaVersion) {
        this.name = name;
        this.javaVersion = javaVersion;
    }

    /// Returns this channel's dependency manifest key.
    public String getName() {
        return name;
    }

    /// Returns the minimum supported Java feature version.
    public int getJavaVersion() {
        return javaVersion;
    }
}
