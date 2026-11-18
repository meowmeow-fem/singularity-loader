package net.singularitymc.sapi.world;

public class TestBlockRegistration {
    public String blockUniqueID;
    public String modelUrlPath;

    public static void main(String[] args) {
        // Calling it right inside the same class: no class name needed
        registerBlock("sapi:ruby_ore", "models/block/ruby_ore.json");

        boolean valid = isValidBlock("sapi:ruby_ore");
        System.out.println("Valid: " + valid);
    }

    public static void registerBlock(String uid, String upath) {
        System.out.println("uid: " + uid + " | upath: " + upath);
    }

    public static boolean isValidBlock(String uid) {
        return uid != null && !uid.isEmpty();
    }
}