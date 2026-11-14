package net.singularitymc.loader.mod;

import java.nio.file.Path;

/** A jar or directory that contains a valid {@code singularity.mod.json}, before dependency resolution. */
public record ModCandidate(Path path, ModMetadataImpl metadata) {}
