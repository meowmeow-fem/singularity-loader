package net.singularitymc.loader.mod;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.singularitymc.api.mod.ModDependency;
import net.singularitymc.api.mod.ModEnvironment;
import net.singularitymc.api.mod.ModMetadata;
import net.singularitymc.api.version.Version;

/** Parsed {@code singularity.mod.json}. Create instances with {@link ModMetadataParser}. */
public final class ModMetadataImpl implements ModMetadata {
    private final String id;
    private final Version version;
    private final String name;
    private final String description;
    private final List<String> authors;
    private final List<String> contributors;
    private final List<String> license;
    private final Map<String, String> contact;
    private final String icon;
    private final ModEnvironment environment;
    private final Map<String, List<String>> entrypoints;
    private final List<MixinConfigRef> mixins;
    private final List<ModDependency> dependencies;
    private final Map<String, Object> custom;

    ModMetadataImpl(String id, Version version, String name, String description, List<String> authors,
                    List<String> contributors, List<String> license, Map<String, String> contact, String icon,
                    ModEnvironment environment, Map<String, List<String>> entrypoints, List<MixinConfigRef> mixins,
                    List<ModDependency> dependencies, Map<String, Object> custom) {
        this.id = id;
        this.version = version;
        this.name = name;
        this.description = description;
        this.authors = List.copyOf(authors);
        this.contributors = List.copyOf(contributors);
        this.license = List.copyOf(license);
        this.contact = Map.copyOf(contact);
        this.icon = icon;
        this.environment = environment;
        this.entrypoints = entrypoints;
        this.mixins = List.copyOf(mixins);
        this.dependencies = List.copyOf(dependencies);
        this.custom = custom;
    }

    /** Metadata for a pseudo mod provided by the loader itself. */
    static ModMetadataImpl builtin(String id, Version version, String name, String description) {
        return new ModMetadataImpl(id, version, name, description, List.of(), List.of(), List.of(), Map.of(), null,
                ModEnvironment.UNIVERSAL, Map.of(), List.of(), List.of(), Map.of());
    }

    @Override public String getId() { return id; }
    @Override public Version getVersion() { return version; }
    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public List<String> getAuthors() { return authors; }
    @Override public List<String> getContributors() { return contributors; }
    @Override public List<String> getLicense() { return license; }
    @Override public Map<String, String> getContact() { return contact; }
    @Override public Optional<String> getIcon() { return Optional.ofNullable(icon); }
    @Override public ModEnvironment getEnvironment() { return environment; }
    @Override public Collection<ModDependency> getDependencies() { return dependencies; }
    @Override public Map<String, Object> getCustomValues() { return custom; }

    /** Entrypoint key to class names, in declaration order. */
    public Map<String, List<String>> getEntrypoints() { return entrypoints; }

    public List<MixinConfigRef> getMixinConfigs() { return mixins; }

    @Override
    public String toString() {
        return id + "@" + version;
    }
}
