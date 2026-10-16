/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
