package tech.qiantong.qdata.security.web.service;

import java.util.Set;

/** These taxonomies are shared platform metadata, with no project ownership column. */
public final class SharedCatalogPermissions {
 private SharedCatalogPermissions() {}
 private static final Set<String> RESOURCES=Set.of("att:dataElemCat","att:tagCat","att:modelCat","att:documentCat","att:cleanCat","att:assetCat","att:apiCat","att:qualityCat","att:theme","att:theme:theme","dm:businesscategory","dm:themeDomain","dm:dataLayer","dm:dataDomain","dg:dataElemCat","dg:dataCategoryCat","mc:domain");
 public static String canonical(String permission) {
  if(permission==null)return null;
  String value=permission.trim();return value.startsWith("att:theme:theme:")?"att:theme:"+value.substring("att:theme:theme:".length()):value;
 }
 public static boolean mutation(String permission) {
  if(permission==null)return false;String value=permission.trim();int end=value.lastIndexOf(':');
  return end>0 && RESOURCES.contains(value.substring(0,end)) && Set.of("add","edit","remove","import").contains(value.substring(end+1));
 }
}
