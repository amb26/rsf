/*
 * Created on 2 Aug 2007
 */
package uk.org.ponder.mapping.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.org.ponder.arrayutil.ListUtil;
import uk.org.ponder.arrayutil.MapUtil;
import uk.org.ponder.beanutil.BeanGetter;
import uk.org.ponder.beanutil.BeanResolver;
import uk.org.ponder.beanutil.PathUtil;
import uk.org.ponder.mapping.DARReshaper;
import uk.org.ponder.mapping.DataConverter;
import uk.org.ponder.mapping.ShellInfo;
import uk.org.ponder.reflect.ReflectUtils;
import uk.org.ponder.util.Logger;

/**
 * A registry of {@link DataConverter} elements, capable of resolving them into
 * the canonical forms of {@link DARReshaper}s and {@link BeanResolver}s, and
 * assessing for any proposed operation which of the converters are applicable.
 * <p>
 * The registry of converters is assumed static and will be fully evaluated when
 * this bean starts up.
 * </p>
 * @author Antranig Basman (amb26@ponder.org.uk)
 */
public class DataConverterRegistry {
  // A false key representing the converters which are registered at the root
  // EL path, not associated with any particular Class
  private static final Class ROOT_CLASS = Math.class;
  private List converters;

  public void setELEvaluator(BeanGetter beanGetter) {
    this.beanGetter = beanGetter;
  }

  public void setConverters(List converters) {
    this.converters = converters;
  }

  private BeanGetter beanGetter;

  private Map byClass = new HashMap();

  public void init() {
    if (converters == null) return;
    for (int i = 0; i < converters.size(); ++i) {
      DataConverter converter = (DataConverter) converters.get(i);
      if (converter.getConverter() == null && converter.getConverterEL() == null) {
        throw new IllegalArgumentException("Converter matching path " + 
            converter.getTargetPath() + " has neither converter nor converterEL set"); 
      }
      Class key = converter.getTargetClass();
      if (key == null)
        key = ROOT_CLASS;
      MapUtil.putMultiMap(byClass, key, converter);
    }
  }

  private static class ConverterCandidate {
    public DataConverter converter;
    public String[] segments;
    public int consumed = 0;

    public ConverterCandidate(DataConverter converter) {
      this.converter = converter;
      if (converter.getTargetPath() != null) {
        segments = PathUtil.splitPath(converter.getTargetPath());
      }
    }
  }

  public Object fetchConverter(ShellInfo shellinfo) {
    String[] segments = shellinfo.segments;
    List candidates = new ArrayList();
    List rootconverters = fetchConverters(ListUtil.instance(ROOT_CLASS));
    accreteCandidates(candidates, rootconverters);
    for (int i = 0; i < segments.length; ++i) {
      Object shell = (i + 1) >= shellinfo.shells.length ? null
          : shellinfo.shells[i + 1];
      List clazzes = shell == null ? new ArrayList()
          : ReflectUtils.getSuperclasses(shell.getClass());
      filterCandidates(candidates, segments[i]);
      List converters = fetchConverters(clazzes);
      accreteCandidates(candidates, converters);
    }
    filterCandidates(candidates, null);
    if (candidates.size() == 0) return null;
    if (candidates.size() > 1) {
      Logger.log.warn("Warning: duplicate DataConverter candidates discovered for EL path " 
          + PathUtil.buildPath(shellinfo.segments) + " only the last (probably the most specific) entry will be applied.");
    }
    ConverterCandidate candidate = (ConverterCandidate) candidates.get(candidates.size() - 1);
    Object converter = candidate.converter.getConverter();
    if (converter != null) {
      return converter;
    }
    String el = candidate.converter.getConverterEL();
    if (el != null) {
      return beanGetter.getBean(el);
    }
    else return null;
  }

  private void filterCandidates(List candidates, String segment) {
    for (int i = candidates.size() - 1; i >= 0; --i) {
      ConverterCandidate candidate = (ConverterCandidate) candidates.get(i);
      boolean incomplete = candidate.segments != null
        && candidate.consumed < candidate.segments.length; 
      if (incomplete && segment != null) {
        String matchseg = candidate.segments[candidate.consumed];
        candidate.consumed ++;
        if (matchseg.equals("*") || matchseg.equals(segment))
          continue;
      }
      // if there is no segment, we need to have consumed all the segments
      // at this point.
      if (segment != null || incomplete) {
        candidates.remove(i);
      }
    }
  }

  private static void accreteCandidates(List candidates, List converters) {
    for (int i = 0; i < converters.size(); ++i) {
      DataConverter converter = (DataConverter) converters.get(i);
      candidates.add(new ConverterCandidate(converter));
    }
  }

  /** Returns all converters registered for the supplied classes * */
  private List fetchConverters(List clazzes) {
    ArrayList togo = new ArrayList();
    for (int i = 0; i < clazzes.size(); ++i) {
      List things = (List) byClass.get(clazzes.get(i));
      if (things != null) {
        togo.addAll(things);
      }
    }
    return togo;
  }
  
  // If there is a DARReceiver in the way, we will not be able to process any
  // by-Class matches - however, we *will* be able to process by-Path matches.
  // the list of shells will therefore be incomplete.
  public DARReshaper fetchReshaper(ShellInfo shellinfo) {
    Object converter = fetchConverter(shellinfo);
    if (converter != null) {
      return ConverterConverter.toReshaper(converter);
    }
    else return null;
  }

}
