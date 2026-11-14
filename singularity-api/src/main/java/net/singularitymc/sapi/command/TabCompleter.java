package net.singularitymc.sapi.command;

import java.util.List;

/** Suggests words for the argument being typed. */
@FunctionalInterface
public interface TabCompleter {
    /**
     * @param args the words typed so far; the last one is the one being completed (empty right after a space)
     * @return suggestions for the last word; those not starting with what was typed are filtered out for you
     */
    List<String> complete(CommandSender sender, String label, String[] args);
}
