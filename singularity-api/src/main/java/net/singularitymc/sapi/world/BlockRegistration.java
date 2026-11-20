package net.singularitymc.sapi.world;

// Imports
import net.singularitymc.loader.util.Log;

public class BlockRegistration {
    public String ModID;
    public String UniqueID;
    public String BlockName;
    public String ModelPathURL;
    public int BlockHardness;
    public boolean RequiredTool;
    public enum ToolLists {
        NONE("NONE"),
        SWORD("SWORD"),
        AXE("AXE"),
        PICKAXE("PICKAXE"),
        SHOVEL("SHOVEL"),
        HOE("HOE");

        public final String tools;

        ToolLists(String tools) {
            this.tools = tools;
        }

    };

    public static void registerBlock(
        String ModID,
         String  UniqueID,
          String BlockName,
           String ModelPathURL,
            int BlockHardness,
             boolean RequiredTool,
              ToolLists toolLists
            ) {

              System.out.println("Mod ID: " + ModID);
              System.out.println("Unique ID: " + UniqueID);
              System.out.println("Block Name: " + BlockName);
              System.out.println("Model Path URL: " + ModelPathURL);
              System.out.println("BlockHardness: " + BlockHardness);
              if (!RequiredTool || toolList == ToolLists.NONE) {
                System.out.println("No Tools Required");
              } else if (RequiredTool) {
                System.out.println("Blocks Required a tool to be broken");
                System.out.println("Tools needed: " + toolLists);
              }

    }
}