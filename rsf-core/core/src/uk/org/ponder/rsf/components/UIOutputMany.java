/*
 * Created on Jan 17, 2006
 */
package uk.org.ponder.rsf.components;

public class UIOutputMany extends UIBoundList {

  /** A constructor suitable for the value lists appearing in selection controls */
  public static UIOutputMany make(String valuebinding, String resolver) {
    UIOutputMany togo = new UIOutputMany();
    togo.valuebinding = new ELReference(valuebinding);
    togo.resolver = new ELReference(resolver);
    return togo;
  }
}
