package net.singularitymc.sapi.internal;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.permission.PermissionDefault;
import net.singularitymc.sapi.permission.PermissionProvider;
import net.singularitymc.sapi.permission.Permissions;

public final class PermissionsImpl implements Permissions {
    private final Map<String, PermissionDefault> defaults = new ConcurrentHashMap<>();
    private volatile PermissionProvider provider;

    @Override
    public void register(String node, PermissionDefault def, String description) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(def, "default");
        defaults.put(node, def);
    }

    @Override
    public PermissionDefault defaultOf(String node) {
        return defaults.getOrDefault(node, PermissionDefault.OP);
    }

    @Override
    public Map<String, PermissionDefault> all() {
        return new TreeMap<>(defaults);
    }

    @Override
    public void setProvider(PermissionProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean check(Player player, String node) {
        PermissionProvider p = provider;
        if (p != null) {
            Boolean decided = p.check(player, node);
            if (decided != null) {
                return decided;
            }
        }
        return defaultOf(node).test(player.isOp());
    }
}
