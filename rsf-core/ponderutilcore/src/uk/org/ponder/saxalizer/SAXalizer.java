package uk.org.ponder.saxalizer;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import org.xml.sax.AttributeList;
import org.xml.sax.HandlerBase;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import uk.org.ponder.saxalizer.mapping.ClassNameManager;
import uk.org.ponder.stringutil.CharWrap;
import uk.org.ponder.util.AssertionException;
import uk.org.ponder.util.Denumeration;
import uk.org.ponder.util.EnumerationConverter;
import uk.org.ponder.util.Logger;
import uk.org.ponder.util.UniversalRuntimeException;

/**
 * The SAXalizer class is used to deserialize a tree of XML tags into a tree of
 * Java objects. Please note that this class is over 5 years old and is due
 * for a very major sandblasting - many comments are extremely out of date,
 * in addition to its using the extremely obsolete SAX1 interface. See wiki
 * for general comments on roadmap for this dinosaur.
 * <p>
 * Every class that wishes to be SAXalized must implement (at least) the
 * interface SAXalizable, which allows the class to report the set methods it
 * supports for attaching subobjects to itself that correspond to XML subtags.
 * It may also support the interface SAXalizableAttrs in order to receive the
 * attributes of the XML tag it corresponds to -- if it does not, any such
 * attributes are thrown away.
 * 
 * Note that the class of a SAXalizing object may also implement the
 * DeSAXalizable interface. In this case, if an existing subobject is present
 * and can be delivered through the interface, SAXalizing subobjects will be
 * delivered onto the existing subobject rather than a new one created afresh.
 * 
 * <p>
 * Two "helper" base classes are called GenericSAXImpl, which allows a Java
 * class to store arbitary XML subtags, and SAXalizableExtraAttrs, which allows
 * it to store arbitrary XML attributes. Note that GenericSAXImpl itself
 * implements SAXalizableExtraAttrs. With these two helper classes, the line is
 * blurred between serializable Java objects and objects representing a DOM-like
 * document structure. We can even have a class which uses both styles at once;
 * implementing named methods for particular tags it is interested in, and
 * storing the rest in a "pool" of GenericSAXImpl.
 * 
 * <p>
 * Note that a useful modification of the standard use of SAXAccessMethodSpec to
 * specify a set method is to use "*" as the parameter for the set method
 * argument type. This will instruct the SAXalizer to use the exact tag name of
 * any subtags as the argument to the Class.forName() reflection method; the
 * class thus referenced must a) exist, and b) be a subclass of the actual
 * argument type of the set method specified.
 * 
 * <p>
 * At the bottom (err, top) of the XML tag tree we stop using the SAXalizable
 * interface, since there will be no further subobjects to deliver. Instead, we
 * use a scheme that externally encodes these object's leafiness so that we can
 * efficiently use primitive Java types to represent them such as String and
 * Date. A global registry class called SAXLeafParser stores a hashtable of
 * Class objects to mechanisms which can parse that type from a String. If the
 * SAXalizer discovers the argument type supplied through the SAXalizable
 * interface for a set method is registered with the SAXLeafParser, it uses that
 * mechanism for parsing any character data attached to the tag into a Java
 * object rather than continuing creating SAXalizable objects.
 * 
 * <p>
 * Note that any character data arriving for non--leaf nodes is currently
 * ignored. Should we ever do any true DOM-style parsing using the SAXalizer, a
 * new interface type will be required to deliver it.
 */

public class SAXalizer extends HandlerBase {
  private SAXalizerMappingContext mappingcontext;
  private SAXLeafParser leafparser;

  public SAXalizer(SAXalizerMappingContext mappingcontext) {
    this.mappingcontext = mappingcontext;
    this.leafparser = mappingcontext.saxleafparser;
  }

  /**
   * Sets the EntityResolverStash this SAXalizer will use to resolve entities
   * referred to by the XML document to be parsed.
   * 
   * @param entityresolverstash The required EntityResolverStash object.
   */

  public void setEntityResolverStash(EntityResolverStash entityresolverstash) {
    this.entityresolverstash = entityresolverstash;
  }

  EntityResolverStash entityresolverstash;

  // The ParseContext class represents everything a DocumentHandler method
  // needs to know about the Java object representing the currently parsing
  // tag. The state of the SAXalizer consists of a stack of ParseContext
  // objects representing the current path to the root XML tag.

  static class ParseContext {
    // The object being constructed; this is created on startElement for
    // SAXalizable-style XML peers, but only created immediately before
    // calling the SAXalizable set method of the parent object in the case
    // of a Leaf-style XML peer. For the leaf case, this object doubles as
    // storage for the Class object for the leaf type.
    Object object;
    MethodAnalyser ma;
    // The SET method in the parent that will be used to deliver this
    // object when it is complete.
    AccessMethod parentsetter;
    // where the parent is an array or collection, this is used to deliver
    // multiple children. If it is simply an enumeration, a normal setMethod
    // is called multiple times.
    // QQQQQ economise on this at some point! One hashmap created per collection.
    HashMap denumerationmap;
    // Is this object using the GenericSAXImpl object lookup scheme
    boolean isgeneric;
    // Is this object using the polymorphic-style "*" object lookup scheme
    //    boolean ispoly;
    // Is this object a leaf-style object
    boolean isleaf;
    // The character data seen so far
    CharWrap textsofar;

    ParseContext(Object object, MethodAnalyser ma, boolean isgeneric,
        boolean isleaf, AccessMethod parentsetter) {
      this.object = object;
      this.ma = ma;
      this.isgeneric = isgeneric;
      this.isleaf = isleaf;
      this.parentsetter = parentsetter;
      textsofar = new CharWrap();
    }
    public boolean hasDenumeration(String tag) {
      if (denumerationmap == null) {
        denumerationmap = new HashMap();
      }
      return (denumerationmap.get(tag) != null); 
    }
    public Denumeration getDenumeration(String tag) {
      if (denumerationmap == null) return null;
      else return (Denumeration)denumerationmap.get(tag);
    }
  }

  // A stack of ParseContexts
  private Stack saxingobjects = new Stack();

  // Return the Saxing object at the top of the stack.
  private ParseContext getSaxingObject() {
    return (ParseContext) (saxingobjects.empty() ? null : saxingobjects.peek());
  }

  /**
   * Blasts any parse-specific state held by this SAXalizer object, making it
   * ready to start a fresh parse.
   */
  public void blastState() {
    saxingobjects.removeAllElements();
  }

  // Given a Class object, push a ParseContect object for that type onto the
  // saxing objects stack. If the object is a leaf type (i.e. it has no
  // subobjects and is
  // registered with the leafparser), do not actually create the required object
  // instance yet; instead, we store the leaf type CLASS itself!!!!
  // An object is either SAXalizable or a leaf type.
  private void pushObject(Class topush, Object oldinstance,
      SAXAccessMethod parentsetter) {
    if (topush.isPrimitive()) {
      topush = leafparser.wrapClass(topush);
    }
    boolean isgeneric = GenericSAX.class.isAssignableFrom(topush);
    boolean isleaf = leafparser.isLeafType(topush);
    // parentsetter is null for the root object only.
    boolean isdenumerable = parentsetter == null? false : 
      parentsetter.isDenumerable();
    // The creation of leaf objects is deferred until all their data has
    // arrived.
    Object newinstance = null;
    try {
      // TODO: This line here is the general battleground - we would really LIKE to
      // be able to (in most cases) determine which type of container to create - 
      // and in addition use FastClass.
      if (oldinstance == null || isdenumerable)
        newinstance = isleaf ? topush : topush.newInstance();
      else newinstance = oldinstance;
    }
    catch (Throwable t) { // IllegalAccessException, InstantiationException
      throw new AssertionException("Cannot create instance of " + topush
          + " using default constructor: " + t.getClass().getName());
    }
    MethodAnalyser ma = isleaf ? null : MethodAnalyser.getMethodAnalyser(
        newinstance, mappingcontext);
    // "reach into the past" and note that we are now within a denumeration.
    // For denumerable types, oldinstance will be the previously obtained
    // container class, and newinstance will be of the containee type.
    if (isdenumerable) {
      ParseContext beingparsed = getSaxingObject();
      if (!beingparsed.hasDenumeration(parentsetter.tagname)) {
        Denumeration den = EnumerationConverter.getDenumeration(oldinstance);
        if (den == null) {
          throw new UniversalRuntimeException("Child " + oldinstance + " in " + 
              topush + " cannot be made denumerable via setter with tag name " 
              + parentsetter.tagname);
        }
        beingparsed.denumerationmap.put(parentsetter.tagname, den);
      }
    }
    saxingobjects.push(new ParseContext(newinstance, ma, isgeneric, isleaf,
        parentsetter));
  }

  //  private CharWrap attributebuffer = new CharWrap();
  // Try to send the attribute list to the object on top of the stack ---
  // if it does not support the SAXalizableAttrs interface,
  // the attributes will be simply thrown away.
  private static void tryBlastAttrs(AttributeList attrlist,
      SAXAccessMethodHash attrmethods, Object obj, SAXLeafParser leafparser,
      boolean waspolymorphic)
      throws SAXException {
    SAXalizableExtraAttrs extraattrs = obj instanceof SAXalizableExtraAttrs?
        (SAXalizableExtraAttrs)obj : null;
    boolean takesextras = extraattrs != null;
    // use up each of the non-"extra" attributes one by one, and send any
    // remaining 
    // ones into SAXalizableExtraAttrs
    Map extras = takesextras? extraattrs.getAttributes() : null; 
    boolean[] expended = takesextras ? new boolean[attrlist.getLength()] : null;

    for (int i = 0; i < attrlist.getLength(); ++i) {
      String attrname = attrlist.getName(i);
      String attrvalue = attrlist.getValue(i);
      if (attrname.equals(ClassNameManager.TYPE_ATTRIBUTE_NAME) &&
          waspolymorphic) continue;
      SAXAccessMethod setattrmethod = attrmethods.get(attrname);
      if (setattrmethod != null) { // if the attribute name is mapped to an
        // access method
        // parse the attribute
        //	  System.out.println("Discovered a method capable of accepting
        // attribute" + attrlist.getName(i));
        //	  attributebuffer.clear().append(attrlist.getValue(i));
        Object newchild = leafparser.parse(setattrmethod.clazz, attrvalue);

        setattrmethod.setChildObject(obj, newchild); // invoke iiiiiit!
        if (takesextras)
          expended[i] = true;
      }
      else if (takesextras) { // if not mapped, and it takes extras,
        extras.put(attrname, attrvalue);
      }
      else { // if all else fails, look for a default map member 
        // QQQQQ implement maps for main tags too.
        SAXAccessMethod defaultattrmethod = attrmethods.get("");
        if (defaultattrmethod == null) {
          throw new UniversalRuntimeException("Couldn't locate handler for attribute "
               + attrname + " in object " + obj.getClass());
        }
        Object newchild = leafparser.parse(defaultattrmethod.clazz, attrvalue);
        Map defaultmap = EnumerationConverter.getMap(defaultattrmethod.getChildObject(obj));
        defaultmap.put(attrname, newchild);
      }
    } // end for each attribute presented by SAX
   
  } // end tryBlast Attrs

  SAXalizerCallback callback;

  /**
   * Produce a subtree of objects from a SAX stream. This method produces a
   * subtree of objects from a SAX stream, rooted at an object of the class
   * specified, starting by interpreting the following SAX events as producing
   * an object of the type specified. It is assumed that the
   * <code>startElement</code> event for the root object that will be returned
   * has just been seen on the stream, and that all further events in the SAX
   * stream will be directed at this object until the matching
   * <code>endElement</code> event.
   * 
   * @param clazz Produce an object of this type.
   * @param attrlist The attribute list that was attached to the
   *          <code>startElement</code> tag that we just saw.
   * @param callback The caller of this method, who will receive the produced
   *          object on seeing of the <code>endElement</code> tag. At this
   *          point, he should stop forwarding <code>DocumentHandler</code>
   *          events to this object. This parameter may be <code>null</code>.
   * @exception SAXException If an error occured while parsing the supplied
   *              input source.
   */
  public void produceSubtree(Object rootobj, AttributeList attrlist,
      SAXalizerCallback callback) throws SAXException {
    if (!saxingobjects.empty()) {
      throw new AssertionException(
          "Attempt to produce new Subtree whilst another"
              + " parse was in progress");
    }
    this.callback = callback;
    pushObject(rootobj.getClass(), rootobj, null);
    // DARN, which is the correct methodanalyser?
    tryBlastAttrs(attrlist, getSaxingObject().ma.attrmethods, rootobj,
        leafparser, false);
  }

  /** ******* Begin methods for the DocumentHandler interface ******* */

  public InputSource resolveEntity(String publicID, String systemID) {
    Logger.println("SAXalizer was asked to resolve public ID " + publicID
        + " systemID " + systemID, Logger.DEBUG_INFORMATIONAL);
    return entityresolverstash == null ? null : entityresolverstash
        .resolve(publicID);
  }

  private Locator locator;

  public void setDocumentLocator(Locator locator) {
    //    System.out.println("Locator received");
    this.locator = locator;
  }

  /**
   * Implements the DocumentHandler interface.
   * 
   * @param tagname The tag name for the element just seen in the SAX stream.
   * @param attrlist The attribute list of the tag just seen in the SAX stream.
   * @exception SAXException If any exception requires to be propagated from
   *              this interface.
   */
  public void startElement(String tagname, AttributeList attrlist)
      throws SAXException {
//    if (Logger.passDebugLevel(Logger.DEBUG_EXTRA_INFO)) {
//      Logger.println("ELEMENT received to SAXALIZER: " + tagname,
//          Logger.DEBUG_EXTRA_INFO);
//    }
    // an element has started, and we must start to construct an object to put
    // it in.
    // The object on top of the ParseContex stack represents the parent object
    // of this object.
    ParseContext beingparsed = getSaxingObject();
    SAXAccessMethodHash tagmethods = beingparsed.ma.tagmethods;
    // Firstly we will look into its AccessMethodHash to see if the tagname we
    // have just seen
    // has been registered by the class of the parent object.
    SAXAccessMethod am = tagmethods.get(tagname);

    Class newobjclass = null; // for some reason, idiot compiler cannot analyse
    // that this is set
    if (am == null) { // if we failed to find a registered method for this tag
      // name
      //      System.err.println("Did NOT find a suitable parent method");
      //      long time = System.currentTimeMillis();
      try { // attempt to look up the class name now so that the forthcoming if
        // statement can
        // be nicely ordered
        if (tagname.indexOf(':') == -1 && tagname.indexOf('.') != -1)
          newobjclass = Class.forName(tagname);
        if (Logger.passDebugLevel(Logger.DEBUG_EXTRA_INFO)) {
          Logger.println("ELEMENT CLASS direct lookup is " + newobjclass);
        }
      }
      catch (Exception e) {
      } // exception simply indicates that the tag name is not a class
      //      System.out.println("forName took "+ (System.currentTimeMillis() -
      // time));
      if (tagmethods.get("*") != null && newobjclass != null) {
        // if the parent is polymorphic, and class name lookup succeeded, do
        // nothing.
        // Note, this is REALLY blank! newobjtype will be set in the try above.
      }
      else if (beingparsed.object instanceof GenericSAX) { // but the parent is
        // generic
        newobjclass = GenericSAXImpl.class; // Child of generic will always be
        // generic.
      }
      else { // the parent is not generic
        throw new SAXParseException("Unexpected tag '"
            + tagname
            + "' found while parsing child of"
            + (beingparsed.object == null ? " null object" : " object of "
                + beingparsed.object.getClass()) + " which is not generic.",
            locator);
      }
    } // end if no registered method
    else {
      String typeattrname = ClassNameManager.TYPE_ATTRIBUTE_NAME; // QQQQQ genericise this somehow.
      if (am.ispolymorphic) {
        String attrvalue = attrlist.getValue(typeattrname);
        newobjclass = mappingcontext.classnamemanager.findClazz(attrvalue);
        if (newobjclass == null) {
          throw new SAXParseException("Polymorphic tag " + tagname
              + " does not have type specified using \"type\" attribute",
              locator);
        }
      }
      else {
        newobjclass = am.clazz;
      }
    }
    if (Logger.passDebugLevel(Logger.DEBUG_EXTRA_INFO)) {
      Logger.println("ELEMENT CLASS determined to be " + newobjclass);
    }

    // if the parent is DeSAXalizable and we can find a unique non-null object
    // already present at this position, use it rather than creating a new one.
    // do not do this for leaves, since they will be replaced anyway, and in any
    // case the existing object represents the leaf's class.
    Object oldobj = null;
    // enumerations and non-getters are out - we could never write to them.
    // if it is a leaf type it is out, UNLESS it is a multiple in which case it 
    // is denumerable.
    if (am.canGet() && !am.isenumeration && (am.ismultiple || !leafparser.isLeafType(am.clazz)) ) {
      oldobj = am.getChildObject(beingparsed.object);
      if (oldobj != null) {
//        Logger.println("Acquired old object " + oldobj + " from parent "
//            + beingparsed.object + " of class " + am.clazz,
//            Logger.DEBUG_EXTRA_INFO);
      }
    }
    // QQQQQ Note: the class of objects inside bare collections cannot be 
    // determined, and must be provided by explicit mapping. PolyManager?
    pushObject(newobjclass, oldobj, am);
    beingparsed = getSaxingObject();
    if (!beingparsed.isleaf) {
      tryBlastAttrs(attrlist, beingparsed.ma.attrmethods, beingparsed.object,
          leafparser, am.ispolymorphic);
    }
  }

  /**
   * Implements the DocumentHandler interface.
   * 
   * @param ch An array holding the character data seen in the SAX stream.
   * @param start The index of the character data within the supplied array.
   * @param length The length of the character data within the supplied array.
   * @exception SAXException If any exception requires to be propagated from
   *              this interface.
   */

  public void characters(char[] ch, int start, int length) throws SAXException {
    if (saxingobjects.empty()) {
      throw new SAXParseException("Unexpected character data "
          + new String(ch, start, length) + " seen when there"
          + " was no active object being parsed", locator);
    }
    ParseContext beingparsed = getSaxingObject();
    beingparsed.textsofar.append(ch, start, length);
    //    System.err.println("CHARACTERS received at SAXALIZER:
    // "+beingparsed.textsofar);
  }

  /**
   * Implements the DocumentHandler interface.
   * 
   * @param tagname The tag name for the element just closed in the SAX stream.
   * @exception SAXException If any exception requires to be propagated from
   *              this interface.
   */

  public void endElement(String tagname) throws SAXException {
//        Logger.println("END ELEMENT received to SAXALIZER: " + tagname,
//        Logger.DEBUG_EXTRA_INFO);
    if (saxingobjects.empty()) {
      throw new SAXParseException("Unexpected closing tag for " + tagname
          + " seen when there was no active object being parsed", locator);
    }
    // Our task is now to deliver the object to its parent. Firstly take
    // care of three special cases before finishing up.
    ParseContext beingparsed = getSaxingObject();
    SAXAccessMethod bodymethod = beingparsed.ma == null? null : beingparsed.ma.bodymethod;
    
    AccessMethod parentsetter = beingparsed.parentsetter;
    // Test the special cases first, i) leaf node
    if (beingparsed.isleaf) {
      // System.err.println("About to parse leaf element with class
      // "+beingparsed.object.getClass());
      // Leaf node objects are only created at this point
      //            System.out.println("About to parse leaf");
      beingparsed.object = leafparser.parse((Class) beingparsed.object,
          beingparsed.textsofar.toString());
      //      System.out.println("Parsed "+beingparsed.textsofar.+" to
      // "+beingparsed.object);
    }
    else if (beingparsed.isgeneric) {
      // special case ii) generic parent - grab the intermediate text
      //      System.out.println("About to parse generic");
      GenericSAX object = (GenericSAX) beingparsed.object;
      object.setData(beingparsed.textsofar.toString());
      object.setTag(tagname);
    }
    else if (bodymethod != null) {
      // special case iii) A body method is registered to receive text 
      String body = beingparsed.textsofar.toString();
      Object newchild = leafparser.parse(bodymethod.clazz, body);
      bodymethod.setChildObject(beingparsed.object, newchild);
    }
    saxingobjects.pop(); // remove the completed object from the stack.

    // Now we must try to deliver the completed object to the parent object.
    // if nothing left on the stack, provide the root object to our caller and
    // return.
    if (saxingobjects.empty()) {
      if (callback != null)
        callback.productionComplete(beingparsed.object);
      return;
    }

    ParseContext parentcontext = getSaxingObject();
//    Logger.println("SAXing object is " + parentcontext.object,
//        Logger.DEBUG_SUBATOMIC);

    Object parentobject = parentcontext.object;
    Denumeration den = null;
    if ((den = parentcontext.getDenumeration(tagname)) != null) {
      den.add(beingparsed.object);
    }
    else {
      parentsetter.setChildObject(parentobject, beingparsed.object);
    }

    /*
     * // else if we found no set method, and parent was generic, deliver the //
     * object // by GenericSax interface. else if (parentcontext.isgeneric) { //
     * System.out.println("About to add object for "+tagname+" to parent"); //
     * If the parent is a vector, add the child object to its collection
     * GenericSAX parentobject = (GenericSAX) parentcontext.object;
     * parentobject.addChild((GenericSAX) beingparsed.object); }
     */

  } // end method endElement
}