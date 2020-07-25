package com.matt.forgehax.util.mod;

/**
 * Created on 9/4/2017 by fr1kin
 */
public enum Category {
  NONE("N/A", "You should not see this"),
  COMBAT("Combat", "Combat related mods"),
  MOVEMENT("Movement", "Anything related to moving"),
  PLAYER("Player", "Mods that interact with the local player"),
  RENDER("Render", "2D/3D rendering mods"),
  WORLD("World", "Any mod that has to do with the world"),
  MISC("Misc", "Miscellaneous"),
  CHAT("Chat", "Mods related to chat"),
  GUI("GUI", "User Interface modules"),
  EXPLOIT("Exploit", "Mess with server"),
  SERVICE("Service", "Background mods"),
  ;
  
  private final String prettyName;
  private final String description;
  
  Category(String prettyName, String description) {
    this.prettyName = prettyName;
    this.description = description;
  }
  
  public String getPrettyName() {
    return prettyName;
  }
  
  public String getDescription() {
    return description;
  }
}
