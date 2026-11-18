package net.singularitymc.sapi.world;

public class TestBlockRegistration {
    public String blockUniqueID;
    public String modelUrlPath;
    public double hardness;

    public enum toolsList {
        None("None"),
        Sword("Sword"), 
        Axe("Axe"), 
        Pickaxe("Pickaxe"), 
        Shovel("Shovel"), 
        Hoe("Hoe");

        final String tool;

        toolsList(String tool) {
            this.tool = tool;
        }
    }

    public boolean requiredtool;

    public static void main(String[] args) {
        // Added the missing hardness, rtool, and toolsList arguments so it compiles
        registerBlock("sapi:ruby_ore", "models/block/ruby_ore.json", 3.0, false, toolsList.None);

        boolean valid = isValidBlock("sapi:ruby_ore");
        System.out.println("Valid: " + valid);
    }

    public static void registerBlock(String uid, String upath, double hardness, boolean rtool, toolsList list) {
        System.out.println("uid: " + uid + " | upath: " + upath);
        System.out.println("hardness: " + hardness);

        // Checks if rtool is false OR if the tool is set to None
        if (!rtool || list == toolsList.None) {
            System.out.println("no required tool");
        } else {
            System.out.println("required tool: " + rtool);
            System.out.println("tool type : " + list.tool);
        }
    }

    public static boolean isValidBlock(String uid) {
        return uid != null && !uid.isEmpty();
    }
}
