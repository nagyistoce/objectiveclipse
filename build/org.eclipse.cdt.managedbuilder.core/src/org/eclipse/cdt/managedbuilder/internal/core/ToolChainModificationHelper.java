/*******************************************************************************
 * Copyright (c) 2007 Intel Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Intel Corporation - Initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.managedbuilder.internal.core;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.cdt.managedbuilder.core.IResourceInfo;
import org.eclipse.cdt.managedbuilder.core.ITool;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.cdt.managedbuilder.core.ManagedBuilderCorePlugin;
import org.eclipse.cdt.managedbuilder.internal.core.ToolChainModificationHelper.ListMap.CollectionEntry;
import org.eclipse.core.runtime.IConfigurationElement;

class ToolChainModificationHelper {
	
	static class ListMap implements Cloneable {
		private HashMap fMap;
		private CollectionEntrySet fCollectionEntrySet;

		public ListMap(){
			fMap = new HashMap();
		}
		
		public class ValueIter {
			private Map fIterMap; 
			
			public ValueIter() {
				fIterMap = new HashMap(fMap);
				for(Iterator iter = fIterMap.entrySet().iterator(); iter.hasNext();){
					Map.Entry entry = (Map.Entry)iter.next();
					Collection c = (Collection)entry.getValue();
					entry.setValue(c.iterator());
				}
			}
			
			public Iterator get(Object key){
				Iterator iter = (Iterator)fIterMap.get(key);
				if(iter != null && !iter.hasNext()){
					fIterMap.remove(key);
					return null;
				}
				return iter;
			}
		}
		
		public class CollectionEntry {
			private Map.Entry fEntry;
			
			CollectionEntry(Map.Entry entry){
				fEntry = entry;
			}
			
			public Object getKey(){
				return fEntry.getKey();
			}
			
			public List getValue(){
				return (List)fEntry.getValue();
			}

			public boolean equals(Object obj) {
				if(obj == this)
					return true;
				
				if(obj == null)
					return false;
				
				if(!(obj instanceof CollectionEntry))
					return false;
				
				return fEntry.equals(((CollectionEntry)obj).fEntry);
			}

			public int hashCode() {
				return fEntry.hashCode();
			}
		}
		
		private class CollectionEntrySet extends AbstractSet {
			private Set fMapEntrySet;

			private class Iter implements Iterator {
				private Iterator fIter;
				
				private Iter(){
					fIter = fMapEntrySet.iterator();
				}
				public boolean hasNext() {
					return fIter.hasNext();
				}

				public Object next() {
					return new CollectionEntry((Map.Entry)fIter.next());
				}

				public void remove() {
					fIter.remove();
				}
				
			}

			private CollectionEntrySet(){
				fMapEntrySet = fMap.entrySet();
			}

			public Iterator iterator() {
				return new Iter();
			}

			public int size() {
				return fMapEntrySet.size();
			}
		}
	

		public void add(Object key, Object value){
			List l = get(key, true);
			l.add(value);
		}
		
		public List removeAll(Object key){
			return (List)fMap.remove(key);
		}

		public List get(Object key, boolean create){
			List l = (List)fMap.get(key);
			if(l == null && create){
				l = newList(1);
				fMap.put(key, l);
			}
			
			return l;
		}
		
		public Collection valuesToCollection(Collection c){
			if(c == null)
				c = newList(20);
			
			for(Iterator iter = fMap.values().iterator(); iter.hasNext(); ){
				List l = (List)iter.next();
				c.addAll(l);
			}
			
			return c;
		}
		
		protected List newList(int size){
			return new ArrayList(size);
		}

		protected List cloneList(List l){
			return (List)((ArrayList)l).clone();
		}
		
		public Collection putValuesToCollection(Collection c){
			for(Iterator iter = collectionEntrySet().iterator(); iter.hasNext(); ){
				List l = ((CollectionEntry)iter.next()).getValue();
				c.addAll(l);
			}
			return c;
		}

		public void remove(Object key, Object value){
			Collection c = get(key, false);
			if(c != null){
				if(c.remove(value) && c.size() == 0){
					fMap.remove(key);
				}
			}
		}

		public Object get(Object key, int num){
			List l = get(key, false);
			if(l != null){
				return l.get(num);
			}
			return null;
		}

		public Object remove(Object key, int num){
			List l = get(key, false);
			if(l != null){
				Object result = null;
				if(l.size() > num){
					result = l.remove(num);
				}
				
				return result;
			}
			return null;
		}

		public Object removeLast(Object key){
			List l = get(key, false);
			if(l != null){
				Object result = null;
				if(l.size() > 0){
					result = l.remove(l.size() - 1);
				}
				return result;
			}
			return null;
		}

		public void removeAll(Object key, Collection values){
			Collection c = get(key, false);
			if(c != null){
				if(c.removeAll(values) && c.size() == 0){
					fMap.remove(key);
				}
			}
		}
		
		public void clearEmptyLists(){
			for(Iterator iter = fMap.entrySet().iterator(); iter.hasNext(); ){
				Map.Entry entry = (Map.Entry)iter.next();
				if(((List)entry.getValue()).size() == 0)
					iter.remove();
			}
		}

		public Set collectionEntrySet(){
			if(fCollectionEntrySet == null)
				fCollectionEntrySet = new CollectionEntrySet();
			return fCollectionEntrySet;
		}

		public void difference(ListMap map){
			for(Iterator iter = map.fMap.entrySet().iterator(); iter.hasNext(); ){
				Map.Entry entry = (Map.Entry)iter.next();
				Collection thisC = (Collection)fMap.get(entry.getKey());
				if(thisC != null){
					if(thisC.removeAll((Collection)entry.getValue()) && thisC == null){
						fMap.remove(entry.getKey());
					}
				}
			}
		}
		
		public ValueIter valueIter(){
			return new ValueIter();
		}

//		protected Collection createCollection(Object key){
//			return new ArrayList(1);
//		}

		protected Object clone() {
			try {
				ListMap clone = (ListMap)super.clone();
				clone.fMap = (HashMap)fMap.clone();
				for(Iterator iter = clone.fMap.entrySet().iterator(); iter.hasNext();){
					Map.Entry entry = (Map.Entry)iter.next();
					entry.setValue(cloneList((List)entry.getValue()));
				}
			} catch (CloneNotSupportedException e) {
				ManagedBuilderCorePlugin.log(e);
			}
			return null;
		}
		
//		protected Map getMap(boolean create){
//			if(fMap == null && create)
//				fMap = createMap();
//			return fMap;
//		}
//		
//		protected Map createMap(){
//			return new HashMap();
//		}
	}
	
	
//	private static class RealToolToToolIter {
//		private Map fMap;
//		
//		public RealToolToToolIter(Map rtListMap) {
//			fMap = getRealToToolIteratorMap(rtListMap, null);
//		}
//		
//		public ITool next(ITool realTool){
//			Iterator iter = (Iterator)fMap.get(realTool);
//			ITool tool = iter.hasNext() ? (ITool)iter.next() : null;
//			if(!iter.hasNext())
//				fMap.remove(realTool);
//			
//			return tool;
//		}
//	}
	
//	private static ITool[] createRealToolArray(ITool[] tools){
//		ITool[] rts = new ITool[tools.length];
//		for(int i = 0; i < tools.length; i++){
//			ITool t = tools[i];
//			ITool rt = ManagedBuildManager.getRealTool(t);
//			if(rt == null)
//				rt = t;
//			rts[i] = rt;
//		}
//		return rts;
//	}

	
	private static ListMap createRealToToolMap(ITool[] tools, boolean ext){
		ListMap lMap = new ListMap();
		for(int i = 0; i < tools.length; i++){
			ITool tool = tools[i];
			ITool rt = ManagedBuildManager.getRealTool(tool);
			if(rt == null)
				rt = tool;
			ITool t = ext ? ManagedBuildManager.getExtensionTool(tool) : tool;
			if(t == null)
				t = tool;
			lMap.add(rt, t);
		}
		
		return lMap;
	}
	
	private static ListMap calculateDifference(ListMap m1, ListMap m2){
		m1 = (ListMap)m1.clone();
		Set ceSet2 = m2.collectionEntrySet();
		
		for(Iterator iter = ceSet2.iterator(); iter.hasNext(); ){
			CollectionEntry entry = (CollectionEntry)iter.next();
			Collection c1 = (Collection)m2.get(entry.getKey(), false);
			if(c1 != null){
				Collection c2 = entry.getValue();
				int i = c2.size();
				for(Iterator c1Iter = c1.iterator(); i >= 0 && c1Iter.hasNext(); i--){
					c1Iter.next();
					c1Iter.remove();
				}
			}
		}
		
		return m1;
	}

	static public ToolListModificationInfo getModificationInfo(IResourceInfo rcInfo, ITool[] fromTools, ITool[] addedTools, ITool[] removedTools){
		ListMap addedMap = createRealToToolMap(addedTools, false);
		for(int i = 0; i < removedTools.length; i++){
			ITool removedTool = removedTools[i];
			ITool realTool = ManagedBuildManager.getRealTool(removedTool);
			if(realTool == null)
				realTool = removedTool;
			
			addedMap.remove(realTool, 0);
		}
		
		ListMap removedMap = createRealToToolMap(removedTools, false);
		for(int i = 0; i < addedTools.length; i++){
			ITool addedTool = addedTools[i];
			ITool realTool = ManagedBuildManager.getRealTool(addedTool);
			if(realTool == null)
				realTool = addedTool;
			
			removedMap.remove(realTool, 0);
		}
		
		addedMap.clearEmptyLists();
		removedMap.clearEmptyLists();
		
		ListMap curMap = createRealToToolMap(fromTools, false);
		for(Iterator iter = removedMap.collectionEntrySet().iterator(); iter.hasNext();){
			CollectionEntry entry = (CollectionEntry)iter.next();
			List cur = curMap.get(entry.getKey(), false);
			List removed = entry.getValue();
			if(cur != null){
				int numToRemove = removed.size();
				int curSize = cur.size();
				if(curSize <= numToRemove){
					curMap.removeAll(entry.getKey());
				} else {
					for(int i = 0; i < numToRemove; i++){
						cur.remove(0);
					}
				}
			}
		}

		curMap.clearEmptyLists();
		
		for(Iterator iter = addedMap.collectionEntrySet().iterator(); iter.hasNext();){
			CollectionEntry entry = (CollectionEntry)iter.next();
			List cur = curMap.get(entry.getKey(), true);
			List added = entry.getValue();
			int numToAdd = added.size();
			numToAdd -= cur.size();
			for(int i = 0; i < numToAdd; i++){
				cur.add(added.get(i));
			}
			
			if(cur.size() == 0)
				curMap.removeAll(entry.getKey());
		}
		
		curMap.clearEmptyLists();
		
		List resultingList = new ArrayList();
		curMap.putValuesToCollection(resultingList);
		
		return getModificationInfo(rcInfo, fromTools, (ITool[])resultingList.toArray(new ITool[resultingList.size()]));
	}

	static public ToolListModificationInfo getModificationInfo(IResourceInfo rcInfo, ITool[] fromTools, ITool[] toTools){
		
		ListMap curMap = createRealToToolMap(fromTools, false);
		List resultingList = new ArrayList();
		List addedList = new ArrayList(7);
		List remainedList = new ArrayList(7);
		List removedList = new ArrayList(7);
		
		for(int i = 0; i < toTools.length; i++){
			ITool tool = toTools[i];
			ITool realTool = ManagedBuildManager.getRealTool(tool);
			if(realTool == null)
				realTool = tool;
			
			ITool remaining = (ITool)curMap.remove(realTool, 0);
			ToolInfo tInfo;
			if(remaining != null){
				tInfo = new ToolInfo(rcInfo, remaining, ToolInfo.REMAINED);
				remainedList.add(tInfo);
			} else {
				tInfo = new ToolInfo(rcInfo, tool, ToolInfo.ADDED);
				addedList.add(tInfo);
			}
			
			resultingList.add(tInfo);
		}
		
		curMap.valuesToCollection(removedList);
		for(ListIterator iter = removedList.listIterator(); iter.hasNext(); ){
			ITool t = (ITool)iter.next();
			iter.set(new ToolInfo(rcInfo, t, ToolInfo.REMOVED));
		}
		
		calculateConverterTools(rcInfo, removedList, addedList, null, null);
		
		return new ToolListModificationInfo(rcInfo,
				listToArray(resultingList),
				listToArray(addedList), 
				listToArray(removedList),
				listToArray(remainedList));
	}
	
	private static ToolInfo[] listToArray(List list){
		return (ToolInfo[])list.toArray(new ToolInfo[list.size()]);
	}
	
	private static Map calculateConverterTools(IResourceInfo rcInfo, List removedList, List addedList, List remainingRemoved, List remainingAdded){
		if(remainingAdded == null)
			remainingAdded = new ArrayList(addedList.size());
		if(remainingRemoved == null)
			remainingRemoved = new ArrayList(removedList.size());
		
		remainingAdded.clear();
		remainingRemoved.clear();
		
		remainingAdded.addAll(addedList);
		remainingRemoved.addAll(removedList);
		
		Map resultMap = new HashMap();
		
		for(Iterator rIter = remainingRemoved.iterator(); rIter.hasNext();){
			ToolInfo rti = (ToolInfo)rIter.next();
			ITool r = rti.getInitialTool();
			
			if(r == null || r.getParentResourceInfo() != rcInfo)
				continue;
			

			Map map = ManagedBuildManager.getConversionElements(r);
			if(map.size() == 0)
				continue;

			for(Iterator aIter = remainingAdded.iterator(); aIter.hasNext();){
				ToolInfo ati = (ToolInfo)aIter.next();
				ITool a = ati.getBaseTool();
				
				if(a == null || a.getParentResourceInfo() == rcInfo)
					continue;
				
				a = ati.getBaseExtensionTool();
				if(a == null)
					continue;
				
				IConfigurationElement el = getToolConverterElement(r, a);
				if(el != null){
					ConverterInfo ci = new ConverterInfo(rcInfo, r, a, el); 
					resultMap.put(r, ci);
					rIter.remove();
					aIter.remove();
					ati.setConversionInfo(rti, ci);
					rti.setConversionInfo(ati, ci);
					break;
				}
			}
		}
		
		return resultMap;
	}
	
	private static IConfigurationElement getToolConverterElement(ITool fromTool, ITool toTool){
		return ((Tool)fromTool).getConverterModificationElement(toTool);
	}
}
