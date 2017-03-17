/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.cache.FileHashCacheVerificationResult;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import javax.annotation.concurrent.GuardedBy;

/**
 * Decorator class the records information about the paths being hashed as a side effect of
 * producing file hashes required for rule key computation.
 */
public class RecordingProjectFileHashCache implements ProjectFileHashCache {
  private static final Logger LOG = Logger.get(RecordingProjectFileHashCache.class);

  private static final long MAX_ROOT_FILE_SIZE_BYTES = 1024 * 1024;

  private final ProjectFileHashCache delegate;
  private final ProjectFilesystem projectFilesystem;
  @GuardedBy("this")
  private final BuildJobStateFileHashes remoteFileHashes;
  private DistBuildConfig distBuildConfig;
  @GuardedBy("this")
  private final Set<Path> seenPaths;
  @GuardedBy("this")
  private final Set<ArchiveMemberPath> seenArchives;
  private final boolean allRecordedPathsAreAbsolute;
  private boolean materializeCurrentFileDuringPreloading = false;

  public RecordingProjectFileHashCache(
      ProjectFileHashCache delegate,
      BuildJobStateFileHashes remoteFileHashes,
      DistBuildConfig distBuildConfig) {
    this(delegate, remoteFileHashes, distBuildConfig, false);
  }

  public RecordingProjectFileHashCache(
      ProjectFileHashCache delegate,
      BuildJobStateFileHashes remoteFileHashes,
      DistBuildConfig distBuildConfig,
      boolean allRecordedPathsAreAbsolute) {
    this.allRecordedPathsAreAbsolute = allRecordedPathsAreAbsolute;
    this.delegate = delegate;
    this.projectFilesystem = delegate.getFilesystem();
    this.remoteFileHashes = remoteFileHashes;
    this.distBuildConfig = distBuildConfig;
    this.seenPaths = new HashSet<>();
    this.seenArchives = new HashSet<>();

    extractBuckConfigFileHashes();
    extractFilesAtRoot();
  }

  @Override
  public HashCode get(Path relPath) throws IOException {
    checkIsRelative(relPath);
    Queue<Path> remainingPaths = new LinkedList<>();
    remainingPaths.add(relPath);
    while (remainingPaths.size() > 0) {
      Path nextPath = remainingPaths.remove();
      HashCode hashCode = delegate.get(nextPath);
      List<PathWithUnixSeparators> children = ImmutableList.of();
      if (projectFilesystem.isDirectory(nextPath)) {
        children = processDirectory(nextPath, remainingPaths);
      }
      synchronized (this) {
        if (!seenPaths.contains(nextPath)) {
          seenPaths.add(nextPath);
          record(nextPath, Optional.empty(), hashCode, children);
        }
      }
    }

    return delegate.get(relPath);
  }

  private List<PathWithUnixSeparators> processDirectory(Path path, Queue<Path> remainingPaths)
      throws IOException {
    List<PathWithUnixSeparators> childrenRelativePaths = new ArrayList<>();
    for (Path relativeChildPath : projectFilesystem.getDirectoryContents(path)) {
      childrenRelativePaths.add(
          new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(relativeChildPath)));
      remainingPaths.add(relativeChildPath);
    }

    return childrenRelativePaths;
  }

  @Override
  public long getSize(Path path) throws IOException {
    return delegate.getSize(path);
  }

  private static void checkIsRelative(Path path) {
    Preconditions.checkArgument(
        !path.isAbsolute(),
        "Path must be relative. Found [%s] instead.",
        path);
  }

  private Path findRealPath(Path path) {
    try {
      Path realPath = projectFilesystem.resolve(path).toRealPath();
      boolean pathContainedSymLinks =
          !path.toAbsolutePath().normalize().equals(realPath.normalize());

      if (pathContainedSymLinks) {
        LOG.info("Followed path [%s] to real path: [%s]", path.toAbsolutePath(), realPath);
        return realPath;
      }
      return path;
    } catch (Exception ex) {
      LOG.error(ex, "Exception following symlink for path [%s]", path.toAbsolutePath());
      throw new RuntimeException(ex);
    }
  }

  // For given symlink, finds the highest level symlink in the path that points outside the
  // project. This is to avoid collisions/redundant symlink creation during re-materialization.
  // Example notes:
  // In the below examples, /a is the root of the project, and /e is outside the project.
  // Example 1:
  // /a/b/symlink_to_x_y/d -> /e/f/x/y/d
  // (where /a/b -> /e/f, and /e/f/symlink_to_x_y -> /e/f/x/y)
  // returns /a/b -> /e/f
  // Example 2:
  // /a/b/symlink_to_c/d -> /e/f/d
  // (where /a/b/symlink_to_c -> /a/b/c and /a/b/c -> /e/f)
  // returns /a/b/symlink_to_c -> /e/f
  // Note: when re-materializing symlinks we skip any intermediate symlinks inside the project
  // (in Example 2 we will re-materialize /a/b/symlink_to_c -> /e/f, and skip /a/b/c).
  private Pair<Path, Path> findSymlinkRoot(Path symlinkPath) {
    int projectPathComponents = projectFilesystem.getRootPath().getNameCount();
    for (int pathEndIndex = (projectPathComponents + 1);
         pathEndIndex <= symlinkPath.getNameCount();
         pathEndIndex++) {
      // Note: subpath(..) does not return a rooted path, so we need to prepend an additional '/'.
      Path symlinkSubpath = symlinkPath.getRoot().resolve(symlinkPath.subpath(
          0, pathEndIndex));
      Path realSymlinkSubpath = findRealPath(symlinkSubpath);
      boolean realPathOutsideProject =
          !projectFilesystem.getPathRelativeToProjectRoot(realSymlinkSubpath).isPresent();
      if (realPathOutsideProject) {
        return new Pair<>(
            projectFilesystem.getPathRelativeToProjectRoot(
                symlinkSubpath).get(), realSymlinkSubpath);
      }
    }

    throw new RuntimeException(
        String.format(
            "Failed to find root symlink for symlink with path [%s]",
            symlinkPath.toAbsolutePath()));

  }

  private synchronized void record(
      Path relPath,
      Optional<String> memRelPath,
      HashCode hashCode,
      List<PathWithUnixSeparators> children) {
    LOG.verbose("Recording path: [%s]", projectFilesystem.resolve(relPath).toAbsolutePath());

    Optional<Path> pathRelativeToProjectRoot =
        projectFilesystem.getPathRelativeToProjectRoot(relPath);
    BuildJobStateFileHashEntry fileHashEntry = new BuildJobStateFileHashEntry();
    boolean pathIsAbsolute = allRecordedPathsAreAbsolute;
    fileHashEntry.setPathIsAbsolute(pathIsAbsolute);
    Path entryKey = pathIsAbsolute ?
        projectFilesystem.resolve(relPath).toAbsolutePath() :
        pathRelativeToProjectRoot.get();
    boolean isDirectory = projectFilesystem.isDirectory(relPath);
    Path realPath = findRealPath(relPath);
    boolean realPathInsideProject =
        projectFilesystem.getPathRelativeToProjectRoot(realPath).isPresent();

    // Symlink handling:
    // 1) Symlink points inside the project:
    // - We treat it like a regular file when uploading/re-materializing.
    // 2) Symlink points outside the project:
    // - We find the highest level part of the path that points outside the project and upload
    // meta-data about this before it is re-materialized. See findSymlinkRoot() for more details.
    if (!realPathInsideProject && !pathIsAbsolute) {
      Pair<Path, Path> symLinkRootAndTarget =
          findSymlinkRoot(projectFilesystem.resolve(relPath).toAbsolutePath());

      Path symLinkRoot =
          projectFilesystem.getPathRelativeToProjectRoot(symLinkRootAndTarget.getFirst()).get();
      fileHashEntry.setRootSymLink(new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(
          symLinkRoot)));
      fileHashEntry.setRootSymLinkTarget(
          new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(
              symLinkRootAndTarget.getSecond().toAbsolutePath())));
    }

    fileHashEntry.setIsDirectory(isDirectory);
    fileHashEntry.setHashCode(hashCode.toString());
    fileHashEntry.setPath(
        new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(entryKey)));
    if (memRelPath.isPresent()) {
      fileHashEntry.setArchiveMemberPath(memRelPath.get().toString());
    }
    if (!isDirectory && !pathIsAbsolute && realPathInsideProject) {
      try {
        // TODO(shivanker, ruibm): Don't read everything in memory right away.
        Path absPath = projectFilesystem.resolve(relPath).toAbsolutePath();
        fileHashEntry.setContents(Files.readAllBytes(absPath));
        fileHashEntry.setIsExecutable(absPath.toFile().canExecute());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else if (isDirectory && !pathIsAbsolute && realPathInsideProject) {
      fileHashEntry.setChildren(children);
    }

    fileHashEntry.setMaterializeDuringPreloading(materializeCurrentFileDuringPreloading);

    // TODO(alisdair04): handling for symlink to internal directory (including infinite loop).
    remoteFileHashes.addToEntries(fileHashEntry);
  }

  @Override
  public HashCode get(ArchiveMemberPath relPath) throws IOException {
    checkIsRelative(relPath.getArchivePath());
    HashCode hashCode = delegate.get(relPath);
    synchronized (this) {
      if (!seenArchives.contains(relPath)) {
        seenArchives.add(relPath);
        record(
            relPath.getArchivePath(),
            Optional.of(relPath.getMemberPath().toString()),
            hashCode,
            new LinkedList<>());
      }
    }
    return hashCode;
  }

  private void addIfPresent(Set<Path> paths, Optional<Path> path) {
    if (path.isPresent() &&
        projectFilesystem.getPathRelativeToProjectRoot(path.get()).isPresent()) {
      paths.add(path.get());
    }
  }

  private void addAllPresent(Set<Path> pathSet, Optional<ImmutableList<Path>> pathsToAdd) {
    if (pathsToAdd.isPresent()) {
      for (Path path : pathsToAdd.get()) {
        addIfPresent(pathSet, Optional.of(path));
      }
    }
  }

  private synchronized void extractBuckConfigFileHashes() {
    // We want to materialize files during pre-loading for .buckconfig entries
    materializeCurrentFileDuringPreloading = true;

    Set<Path> paths = new HashSet<>();

    // TODO(alisdair04,shivanker): KnownBuildRuleTypes always loads java compilers if they are
    // defined in a .buckconfig, regardless of what type of build is taking place. Unless peforming
    // a Java build, they are not added to the build graph, and as such Stampede needs to be told
    // about them directly via the whitelist.

    // TODO(alisdair04,ruibm): capture all .buckconfig dependencies automatically.

    Optional<ImmutableList<Path>> whitelist = distBuildConfig.getOptionalPathWhitelist();
    LOG.info(
        "Stampede always materialize whitelist: [%s].",
        whitelist.isPresent() ? Joiner.on(", ").join(whitelist.get()) : "");
    addAllPresent(paths, whitelist);

    try {
      for (Path path : paths) {
        get(path);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      materializeCurrentFileDuringPreloading = false;
    }
  }

  private synchronized void extractFilesAtRoot() {
    // We want to materialize files at the root of the repo during pre-loading
    materializeCurrentFileDuringPreloading = true;

    try {
      for (Path path : projectFilesystem.getDirectoryContents(projectFilesystem.getRootPath())) {
        if (projectFilesystem.isFile(path) &&
            !projectFilesystem.isSymLink(path) &&
            path.getFileName().startsWith(".") &&
            projectFilesystem.getFileSize(path) < MAX_ROOT_FILE_SIZE_BYTES) {
          // Force the calculation of the hash which will record the file.
          get(path);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      materializeCurrentFileDuringPreloading = false;
    }
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }

  @Override
  public boolean willGet(Path relPath) {
    return delegate.willGet(relPath);
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberRelPath) {
    return delegate.willGet(archiveMemberRelPath);
  }

  @Override
  public void invalidate(Path path) {
    delegate.invalidate(path);
  }

  @Override
  public void invalidateAll() {
    delegate.invalidateAll();
  }

  @Override
  public void set(Path path, HashCode hashCode) throws IOException {
    delegate.set(path, hashCode);
  }

  @Override
  public FileHashCacheVerificationResult verify() throws IOException {
    return delegate.verify();
  }
}
