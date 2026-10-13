/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.gradle.pack;

import kala.compress.archivers.cpio.CpioArchiveEntry;
import kala.compress.archivers.cpio.CpioArchiveOutputStream;
import kala.compress.archivers.cpio.CpioConstants;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/// Creates an RPM package for the current HMCL channel.
///
/// The package is assembled entirely in user space, mirroring [CreateDeb], so
/// neither `rpmbuild` nor a rooted build environment is required. The output is
/// an unsigned, gzip-compressed `noarch` RPM in the version 4 format.
///
/// ## Package layout
///
/// The payload installs the same four artifacts as the Debian package:
///
/// - the bundled HMCL shell launcher under `/usr/share/java/hmcl/`
/// - a channel-specific command under `/usr/bin/`
/// - a desktop entry under `/usr/share/applications/`
/// - the HMCL icon under `/usr/share/icons/hicolor/256x256/apps/`
///
/// ## Channel commands and aliases
///
/// Every package installs a channel-specific executable such as `hmcl-stable`
/// or `hmcl-beta` and registers it into the shared `hmcl` alternatives group
/// through `%post` and `%preun` scriptlets, matching the Debian package.
@NotNullByDefault
public abstract class CreateRpm extends DefaultTask {

    /// Logger used for progress messages.
    public static final Logger LOGGER = Logging.getLogger(CreateRpm.class);

    /// Full cpio mode of an installed directory.
    private static final int DIRECTORY_MODE = CpioConstants.C_ISDIR | LinuxPackageFiles.DIRECTORY_PERMISSIONS;

    /// Full cpio mode of an installed executable.
    private static final int EXECUTABLE_MODE = CpioConstants.C_ISREG | LinuxPackageFiles.EXECUTABLE_PERMISSIONS;

    /// Full cpio mode of a regular data file.
    private static final int REGULAR_FILE_MODE = CpioConstants.C_ISREG | LinuxPackageFiles.REGULAR_FILE_PERMISSIONS;

    /// Region tag identifying the immutable region of a package header.
    private static final int HEADER_REGION_TAG = 63;

    /// Region tag identifying the immutable region of a signature header.
    private static final int SIGNATURE_REGION_TAG = 62;

    /// RPM header tag for the locale table.
    private static final int TAG_HEADER_I18NTABLE = 100;
    /// RPM header tag for the package name.
    private static final int TAG_NAME = 1000;
    /// RPM header tag for the upstream version.
    private static final int TAG_VERSION = 1001;
    /// RPM header tag for the package release.
    private static final int TAG_RELEASE = 1002;
    /// RPM header tag for the one-line summary.
    private static final int TAG_SUMMARY = 1004;
    /// RPM header tag for the multi-line description.
    private static final int TAG_DESCRIPTION = 1005;
    /// RPM header tag for the build timestamp.
    private static final int TAG_BUILD_TIME = 1006;
    /// RPM header tag for the build host name.
    private static final int TAG_BUILD_HOST = 1007;
    /// RPM header tag for the total installed size.
    private static final int TAG_SIZE = 1009;
    /// RPM header tag for the license identifier.
    private static final int TAG_LICENSE = 1014;
    /// RPM header tag for the package group.
    private static final int TAG_GROUP = 1016;
    /// RPM header tag for the project URL.
    private static final int TAG_URL = 1020;
    /// RPM header tag for the target operating system.
    private static final int TAG_OS = 1021;
    /// RPM header tag for the target architecture.
    private static final int TAG_ARCH = 1022;
    /// RPM header tag for the post-install scriptlet body.
    private static final int TAG_POST_IN = 1024;
    /// RPM header tag for the pre-uninstall scriptlet body.
    private static final int TAG_PRE_UN = 1025;
    /// RPM header tag for the per-file sizes.
    private static final int TAG_FILE_SIZES = 1028;
    /// RPM header tag for the per-file modes.
    private static final int TAG_FILE_MODES = 1030;
    /// RPM header tag for the per-file modification times.
    private static final int TAG_FILE_MTIMES = 1034;
    /// RPM header tag for the per-file digests.
    private static final int TAG_FILE_DIGESTS = 1035;
    /// RPM header tag for the per-file symlink targets.
    private static final int TAG_FILE_LINKTOS = 1036;
    /// RPM header tag for the per-file owner names.
    private static final int TAG_FILE_USERNAMES = 1039;
    /// RPM header tag for the per-file group names.
    private static final int TAG_FILE_GROUPNAMES = 1040;
    /// RPM header tag for the originating source package.
    private static final int TAG_SOURCE_RPM = 1044;
    /// RPM header tag for the per-file verification flags.
    private static final int TAG_FILE_VERIFY_FLAGS = 1045;
    /// RPM header tag for the capabilities provided by the package.
    private static final int TAG_PROVIDE_NAMES = 1047;
    /// RPM header tag for the RPM version used to build the package.
    private static final int TAG_RPM_VERSION = 1064;
    /// RPM header tag for the post-install scriptlet interpreter.
    private static final int TAG_POST_IN_PROG = 1086;
    /// RPM header tag for the pre-uninstall scriptlet interpreter.
    private static final int TAG_PRE_UN_PROG = 1087;
    /// RPM header tag for the provide flags.
    private static final int TAG_PROVIDE_FLAGS = 1112;
    /// RPM header tag for the provide versions.
    private static final int TAG_PROVIDE_VERSIONS = 1113;
    /// RPM header tag for the parent directory index of every file.
    private static final int TAG_DIR_INDEXES = 1116;
    /// RPM header tag for the basename of every file.
    private static final int TAG_BASE_NAMES = 1117;
    /// RPM header tag for the unique parent directories.
    private static final int TAG_DIR_NAMES = 1118;
    /// RPM header tag for the payload archive format.
    private static final int TAG_PAYLOAD_FORMAT = 1124;
    /// RPM header tag for the payload compressor.
    private static final int TAG_PAYLOAD_COMPRESSOR = 1125;
    /// RPM header tag for the payload compressor level.
    private static final int TAG_PAYLOAD_FLAGS = 1126;
    /// RPM header tag for the per-file digest algorithm.
    private static final int TAG_FILE_DIGEST_ALGO = 5011;
    /// RPM header tag for the header string encoding.
    private static final int TAG_ENCODING = 5062;
    /// RPM header tag for the digest of the compressed payload.
    private static final int TAG_PAYLOAD_DIGEST = 5092;
    /// RPM header tag for the payload digest algorithm.
    private static final int TAG_PAYLOAD_DIGEST_ALGO = 5093;
    /// RPM header tag for the digest of the uncompressed payload.
    private static final int TAG_PAYLOAD_DIGEST_ALT = 5097;

    /// RPM signature tag for the SHA-1 digest of the header.
    private static final int SIG_SHA1 = 269;
    /// RPM signature tag for the SHA-256 digest of the header.
    private static final int SIG_SHA256 = 273;
    /// RPM signature tag for the combined header and payload size.
    private static final int SIG_SIZE = 1000;
    /// RPM signature tag for the MD5 digest of the header and payload.
    private static final int SIG_MD5 = 1004;
    /// RPM signature tag for the compressed payload size.
    private static final int SIG_PAYLOAD_SIZE = 1007;

    /// Identifier of the SHA-256 digest algorithm in RPM metadata.
    private static final int DIGEST_ALGO_SHA256 = 8;

    /// RPM mask enabling all supported file verification checks.
    private static final int VERIFY_ALL = ~0;

    /// RPM sense flag marking a versioned, equal capability.
    private static final int SENSE_EQUAL = 8;

    /// RPM version reported in the package metadata.
    private static final String BUILT_BY_RPM_VERSION = "4.16.0";

    /// Package version written into the `Version` tag and output filename.
    @Input
    public abstract Property<String> getVersion();

    /// Release type metadata that controls package name, launcher name, and alias priority.
    @Input
    public abstract Property<ReleaseType> getReleaseType();

    /// Launcher class name for the Linux `StartupWMClass` property in the desktop file.
    @Input
    public abstract Property<String> getLauncherClassName();

    /// Executable `.sh` artifact produced by `makeExecutables`.
    @InputFile
    public abstract RegularFileProperty getAppShFile();

    /// Desktop icon installed into the hicolor icon theme.
    @InputFile
    public abstract RegularFileProperty getIconFile();

    /// Final `.rpm` archive written by this task.
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /// Optional reproducible-build timestamp, in seconds since the Unix epoch.
    ///
    /// When present it is recorded in the `Buildtime` tag and in every file entry
    /// so repeated builds with the same inputs produce identical packages.
    @Input
    @Optional
    public abstract Property<Long> getBuildTimestamp();

    /// Builds the payload, header, signature and lead, then writes the package.
    ///
    /// @throws IOException if reading an input artifact or writing the package fails
    @TaskAction
    public void run() throws IOException {
        Path appShFile = getAppShFile().getAsFile().get().toPath();
        if (!Files.isRegularFile(appShFile))
            throw new IOException("Invalid app script file: " + appShFile);

        Path iconFile = getIconFile().getAsFile().get().toPath();
        if (!Files.isRegularFile(iconFile))
            throw new IOException("Invalid icon file: " + iconFile);

        byte[] appShBytes = Files.readAllBytes(appShFile);
        if (appShBytes.length == 0)
            throw new IOException("Empty app script file: " + appShFile);

        byte[] iconBytes = Files.readAllBytes(iconFile);
        if (iconBytes.length == 0)
            throw new IOException("Empty icon file: " + iconFile);

        ReleaseType releaseType = getReleaseType().get();
        long buildTime = getBuildTimestamp().isPresent()
                ? getBuildTimestamp().get()
                : Instant.now().getEpochSecond();
        if (buildTime < 0 || buildTime > Integer.MAX_VALUE)
            throw new GradleException("RPM build timestamp is outside the supported 32-bit range: " + buildTime);

        String packageVersion = sanitizeVersion(getVersion().get());
        String packageRelease = "1";
        LinuxPackageFiles linuxFiles = new LinuxPackageFiles(
                releaseType, appShFile.getFileName().toString(), getLauncherClassName().get());

        List<RpmFile> files = buildFileList(appShBytes, iconBytes, linuxFiles, buildTime);

        LOGGER.lifecycle("Creating cpio payload");
        byte[] cpio = createCpioArchive(files);
        byte[] payload = gzip(cpio);

        LOGGER.lifecycle("Creating RPM header");
        byte[] header = createHeader(files, cpio, payload, releaseType, linuxFiles,
                packageVersion, packageRelease, buildTime);

        LOGGER.lifecycle("Creating RPM signature");
        byte[] signature = createSignature(header, payload, cpio.length);

        Path outputFile = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(outputFile.getParent());

        LOGGER.lifecycle("Creating rpm file");
        try (OutputStream output = Files.newOutputStream(outputFile)) {
            output.write(createLead(releaseType, packageVersion, packageRelease));
            output.write(signature);
            output.write(header);
            output.write(payload);
        }
    }

    /// Builds the list of artifacts installed by the package.
    private static List<RpmFile> buildFileList(byte[] appShBytes, byte[] iconBytes,
                                               LinuxPackageFiles linuxFiles, long buildTime) {
        List<RpmFile> files = new ArrayList<>();

        files.add(new RpmFile(LinuxPackageFiles.INSTALL_DIRECTORY, DIRECTORY_MODE, new byte[0], buildTime));
        files.add(new RpmFile(linuxFiles.targetPath(), EXECUTABLE_MODE, appShBytes, buildTime));
        files.add(new RpmFile(linuxFiles.launcherPath(), EXECUTABLE_MODE,
                linuxFiles.launcherScript().getBytes(StandardCharsets.UTF_8), buildTime));
        files.add(new RpmFile(linuxFiles.desktopFilePath(), REGULAR_FILE_MODE,
                linuxFiles.desktopInfo().getBytes(StandardCharsets.UTF_8), buildTime));
        files.add(new RpmFile(linuxFiles.iconTargetPath(), REGULAR_FILE_MODE, iconBytes, buildTime));

        return files;
    }

    /// Serializes the file list into an uncompressed SVR4 `newc` cpio archive.
    ///
    /// The project already depends on `kala-compress` for the Debian package, so
    /// its cpio archiver is reused here rather than writing the payload format by
    /// hand. RPM requires `newc` entries with the original absolute path stored
    /// relatively, which the archiver emits when the name is prefixed with `./`.
    private static byte[] createCpioArchive(List<RpmFile> files) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (CpioArchiveOutputStream output = new CpioArchiveOutputStream(buffer, CpioConstants.FORMAT_NEW)) {
            long inode = 1;
            for (RpmFile file : files) {
                // RpmFile paths are absolute, so prefixing with "." yields the "./..." form RPM expects.
                CpioArchiveEntry entry = new CpioArchiveEntry(CpioConstants.FORMAT_NEW,
                        "." + file.path(), file.content().length);
                entry.setMode(file.mode());
                entry.setInode(inode++);
                entry.setUID(0);
                entry.setGID(0);
                entry.setNumberOfLinks(1);
                entry.setTime(file.mtime());
                output.putArchiveEntry(entry);
                if (file.content().length > 0)
                    output.write(file.content());
                output.closeArchiveEntry();
            }
        }
        return buffer.toByteArray();
    }

    /// Gzip-compresses the cpio archive with the maximum compression level.
    private static byte[] gzip(byte @Unmodifiable [] data) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(buffer) {
            {
                def.setLevel(Deflater.BEST_COMPRESSION);
            }
        }) {
            output.write(data);
        }
        return buffer.toByteArray();
    }

    /// Builds the main package header describing metadata and the file list.
    private static byte[] createHeader(List<RpmFile> files, byte[] cpio, byte[] payload,
                                       ReleaseType releaseType, LinuxPackageFiles linuxFiles,
                                       String packageVersion, String packageRelease, long buildTime) throws IOException {
        RpmHeader header = new RpmHeader(HEADER_REGION_TAG);

        int fileCount = files.size();
        int[] fileSizes = new int[fileCount];
        int[] fileModes = new int[fileCount];
        int[] fileMtimes = new int[fileCount];
        int[] fileVerifyFlags = new int[fileCount];
        String[] fileDigests = new String[fileCount];
        String[] fileLinkTos = new String[fileCount];
        String[] fileUserNames = new String[fileCount];
        String[] fileGroupNames = new String[fileCount];
        int[] dirIndexes = new int[fileCount];
        String[] baseNames = new String[fileCount];

        Map<String, Integer> dirNames = new LinkedHashMap<>();
        long installedSize = 0;

        for (int i = 0; i < fileCount; i++) {
            RpmFile file = files.get(i);
            int slash = file.path().lastIndexOf('/');
            String dirName = file.path().substring(0, slash + 1);
            String baseName = file.path().substring(slash + 1);

            dirIndexes[i] = dirNames.computeIfAbsent(dirName, ignored -> dirNames.size());
            baseNames[i] = baseName;
            fileSizes[i] = file.content().length;
            fileModes[i] = file.mode();
            fileMtimes[i] = (int) file.mtime();
            fileVerifyFlags[i] = VERIFY_ALL;
            fileDigests[i] = isRegularFile(file.mode()) ? sha256(file.content()) : "";
            fileLinkTos[i] = "";
            fileUserNames[i] = "root";
            fileGroupNames[i] = "root";

            installedSize += fileSizes[i];
        }

        String packageName = releaseType.getPackageName();
        String provideVersion = packageVersion + "-" + packageRelease;

        header.putStringArray(TAG_HEADER_I18NTABLE, new String[]{"C"});
        header.putString(TAG_NAME, packageName);
        header.putString(TAG_VERSION, packageVersion);
        header.putString(TAG_RELEASE, packageRelease);
        header.putI18nString(TAG_SUMMARY, releaseType.getDisplayName());
        header.putI18nString(TAG_DESCRIPTION, "Hello Minecraft! Launcher");
        header.putInt32(TAG_BUILD_TIME, (int) buildTime);
        header.putString(TAG_BUILD_HOST, "localhost");
        header.putInt32(TAG_SIZE, (int) Math.min(installedSize, Integer.MAX_VALUE));
        header.putString(TAG_LICENSE, "GPL-3.0-or-later");
        header.putI18nString(TAG_GROUP, "Amusements/Games");
        header.putString(TAG_URL, "https://github.com/HMCL-dev/HMCL");
        header.putString(TAG_OS, "linux");
        header.putString(TAG_ARCH, "noarch");
        header.putString(TAG_POST_IN, getPostInstall(linuxFiles, releaseType.getAlternativesPriority()));
        header.putStringArray(TAG_POST_IN_PROG, new String[]{"/bin/sh"});
        header.putString(TAG_PRE_UN, getPreUninstall(linuxFiles));
        header.putStringArray(TAG_PRE_UN_PROG, new String[]{"/bin/sh"});
        header.putInt32(TAG_FILE_SIZES, fileSizes);
        header.putInt16(TAG_FILE_MODES, fileModes);
        header.putInt32(TAG_FILE_MTIMES, fileMtimes);
        header.putStringArray(TAG_FILE_DIGESTS, fileDigests);
        header.putStringArray(TAG_FILE_LINKTOS, fileLinkTos);
        header.putStringArray(TAG_FILE_USERNAMES, fileUserNames);
        header.putStringArray(TAG_FILE_GROUPNAMES, fileGroupNames);
        header.putString(TAG_SOURCE_RPM, "%s-%s-%s.src.rpm".formatted(packageName, packageVersion, packageRelease));
        header.putInt32(TAG_FILE_VERIFY_FLAGS, fileVerifyFlags);
        header.putStringArray(TAG_PROVIDE_NAMES, new String[]{packageName});
        header.putInt32(TAG_PROVIDE_FLAGS, SENSE_EQUAL);
        header.putStringArray(TAG_PROVIDE_VERSIONS, new String[]{provideVersion});
        header.putString(TAG_RPM_VERSION, BUILT_BY_RPM_VERSION);
        header.putInt32(TAG_DIR_INDEXES, dirIndexes);
        header.putStringArray(TAG_BASE_NAMES, baseNames);
        header.putStringArray(TAG_DIR_NAMES, dirNames.keySet().toArray(new String[0]));
        header.putString(TAG_PAYLOAD_FORMAT, "cpio");
        header.putString(TAG_PAYLOAD_COMPRESSOR, "gzip");
        header.putString(TAG_PAYLOAD_FLAGS, "9");
        header.putInt32(TAG_FILE_DIGEST_ALGO, DIGEST_ALGO_SHA256);
        header.putString(TAG_ENCODING, "utf-8");
        header.putStringArray(TAG_PAYLOAD_DIGEST, new String[]{sha256(payload)});
        header.putInt32(TAG_PAYLOAD_DIGEST_ALGO, DIGEST_ALGO_SHA256);
        header.putStringArray(TAG_PAYLOAD_DIGEST_ALT, new String[]{sha256(cpio)});

        return header.build();
    }

    /// Builds the signature header carrying the size and digest information.
    private static byte[] createSignature(byte[] header, byte[] payload, int archiveSize) throws IOException {
        RpmHeader signature = new RpmHeader(SIGNATURE_REGION_TAG);
        signature.putString(SIG_SHA1, sha1(header));
        signature.putString(SIG_SHA256, sha256(header));
        signature.putInt32(SIG_SIZE, header.length + payload.length);
        signature.putBin(SIG_MD5, md5(header, payload));
        signature.putInt32(SIG_PAYLOAD_SIZE, archiveSize);

        byte[] bytes = signature.build();
        int padding = (8 - (bytes.length % 8)) % 8;
        return padding == 0 ? bytes : Arrays.copyOf(bytes, bytes.length + padding);
    }

    /// Builds the historical 96-byte lead that starts every RPM file.
    private static byte[] createLead(ReleaseType releaseType, String packageVersion, String packageRelease) {
        ByteBuffer lead = ByteBuffer.allocate(96);

        lead.put(new byte[]{(byte) 0xed, (byte) 0xab, (byte) 0xee, (byte) 0xdb});
        lead.put((byte) 3); // major
        lead.put((byte) 0); // minor
        lead.putShort((short) 0); // binary package
        lead.putShort((short) 0); // architecture number, historical only

        // The package name is NUL-padded to 66 bytes; the buffer is already zeroed.
        byte[] name = "%s-%s-%s".formatted(releaseType.getPackageName(), packageVersion, packageRelease)
                .getBytes(StandardCharsets.UTF_8);
        int nameLength = Math.min(name.length, 65);
        lead.put(name, 0, nameLength);
        lead.position(lead.position() + (66 - nameLength));

        lead.putShort((short) 1); // OS number, Linux
        lead.putShort((short) 5); // header-style signatures
        lead.put(new byte[16]); // reserved

        return lead.array();
    }

    /// Generates the `%post` scriptlet registering the channel command.
    private static String getPostInstall(LinuxPackageFiles linuxFiles, int alternativesPriority) {
        return """
                #!/bin/sh
                if command -v update-alternatives >/dev/null 2>&1; then
                    update-alternatives --install %s hmcl %s %d
                elif command -v alternatives >/dev/null 2>&1; then
                    alternatives --install %s hmcl %s %d
                fi
                """.formatted(LinuxPackageFiles.COMMON_LAUNCHER_PATH, linuxFiles.launcherPath(),
                alternativesPriority, LinuxPackageFiles.COMMON_LAUNCHER_PATH, linuxFiles.launcherPath(),
                alternativesPriority);
    }

    /// Generates the `%preun` scriptlet removing the channel command.
    private static String getPreUninstall(LinuxPackageFiles linuxFiles) {
        return """
                #!/bin/sh
                if [ "$1" = 0 ]; then
                    if command -v update-alternatives >/dev/null 2>&1; then
                        update-alternatives --remove hmcl %s
                    elif command -v alternatives >/dev/null 2>&1; then
                        alternatives --remove hmcl %s
                    fi
                fi
                """.formatted(linuxFiles.launcherPath(), linuxFiles.launcherPath());
    }

    /// Returns whether the mode describes a regular file, as opposed to a directory.
    private static boolean isRegularFile(int mode) {
        return (mode & CpioConstants.S_IFMT) == CpioConstants.C_ISREG;
    }

    /// Normalizes a project version into a value accepted by the RPM `Version` tag.
    ///
    /// RPM reserves the dash as the separator between the version and the release
    /// and only permits a small character set. Unsupported characters, including
    /// the dash, are replaced by underscores.
    private static String sanitizeVersion(String version) {
        if (version.isBlank())
            throw new GradleException("RPM version must not be blank");

        StringBuilder result = new StringBuilder(version.length());
        for (int i = 0; i < version.length(); i++) {
            char ch = version.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '+' || ch == '~') {
                result.append(ch);
            } else {
                result.append('_');
            }
        }
        return result.toString();
    }

    /// Returns the lowercase hexadecimal SHA-1 digest of `data`.
    private static String sha1(byte @Unmodifiable [] data) {
        return HexFormat.of().formatHex(digest("SHA-1", data));
    }

    /// Returns the lowercase hexadecimal SHA-256 digest of `data`.
    private static String sha256(byte @Unmodifiable [] data) {
        return HexFormat.of().formatHex(digest("SHA-256", data));
    }

    /// Returns the raw MD5 digest of the concatenation of `first` and `second`.
    ///
    /// The digest is fed incrementally so the two inputs are never copied into a
    /// single buffer, which matters because the payload can be several megabytes.
    private static byte @Unmodifiable [] md5(byte @Unmodifiable [] first, byte @Unmodifiable [] second) {
        MessageDigest digest = newDigest("MD5");
        digest.update(first);
        digest.update(second);
        return digest.digest();
    }

    /// Computes a message digest over `data`.
    private static byte @Unmodifiable [] digest(String algorithm, byte @Unmodifiable [] data) {
        return newDigest(algorithm).digest(data);
    }

    /// Creates a message digest, wrapping the checked exception that cannot occur.
    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Unsupported digest algorithm: " + algorithm, e);
        }
    }

    /// One artifact installed by the package.
    ///
    /// @param path    absolute install path
    /// @param mode    full Unix mode including the file-type bits
    /// @param content file content; empty for directories
    /// @param mtime   modification time in seconds since the Unix epoch
    @NotNullByDefault
    private record RpmFile(
            String path,
            int mode,
            byte @Unmodifiable [] content,
            long mtime
    ) {
    }
}
