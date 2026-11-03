package com.example.ems.upgrade;

import java.util.*;

/** Explicit allow-list of conversion edges. A future version must supply and test its own edge. */
public record CompatibilityPlan(String source,String target,Map<String,TableMapping> mappings,Set<String> extensionTables) {
 public record TableMapping(Set<String> exactColumns,Map<String,String> wideningRules,Map<String,String> targetDefaults,Set<String> extensionColumns){}
 private static final Set<String> EDGES=Set.of("canonical-121->canonical-120","canonical-120->canonical-121","legacy-113->canonical-121","legacy-120->canonical-121");
 public static CompatibilityPlan between(String source,String target){
  if(!EDGES.contains(source+"->"+target))throw new IllegalArgumentException("Unregistered conversion edge");
  var from=CompatibilityCatalog.profile(source);var to=CompatibilityCatalog.profile(target);var mappings=new LinkedHashMap<String,TableMapping>();
  for(var entry:to.tables().entrySet()){
   String name=entry.getKey();var destination=entry.getValue();var origin=from.tables().get(name);if(origin==null)continue;
   if(!origin.primaryKey().equals(destination.primaryKey()))throw new IllegalStateException("Registered primary-key conversion requires explicit implementation");
   var common=new TreeSet<String>();var widening=new TreeMap<String,String>();var defaults=new TreeMap<String,String>();var extensions=new TreeSet<>(origin.columns().keySet());
   for(var column:destination.columns().entrySet()){
    var input=origin.columns().get(column.getKey());
    if(input!=null){
     if(!input.type().equals(column.getValue().type())){
      if(source.startsWith("legacy-")&&target.equals("canonical-121")&&name.equals("employee_profile")&&column.getKey().equals("version")&&input.type().equals("INT")&&column.getValue().type().equals("BIGINT"))widening.put(column.getKey(),"SIGNED_INT_TO_BIGINT");
      else throw new IllegalStateException("Non-exact registered type conversion requires explicit implementation");
     }
     common.add(column.getKey());extensions.remove(column.getKey());
    }
    else{
     var value=column.getValue();
     if(value.generated()||value.nullable()||value.defaultValue()!=null)defaults.put(column.getKey(),value.generated()?"GENERATED":value.defaultValue()==null?"NULL":value.defaultValue());
     else if(column.getKey().equals("create_time")&&origin.columns().containsKey("update_time"))defaults.put(column.getKey(),"COPY:update_time");
     else throw new IllegalStateException("Missing mandatory target field requires an explicit rule");
    }
   }
   mappings.put(name,new TableMapping(Collections.unmodifiableSet(common),Collections.unmodifiableMap(widening),Collections.unmodifiableMap(defaults),Collections.unmodifiableSet(extensions)));
  }
  var extensionTables=new TreeSet<>(from.tables().keySet());extensionTables.removeAll(to.tables().keySet());
  return new CompatibilityPlan(source,target,Collections.unmodifiableMap(mappings),Collections.unmodifiableSet(extensionTables));
 }
}
