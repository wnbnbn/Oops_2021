package org.json
// Minimal in-memory serialization shim for state-machine tests, not an Android/provider emulator.
private object Tokens {
    val objects=mutableMapOf<String,Map<String,Any?>>()
    val arrays=mutableMapOf<String,List<Any?>>()
    fun id()=java.util.UUID.randomUUID().toString()
}
class JSONObject {
    private val data:MutableMap<String,Any?>
    constructor() { data=mutableMapOf() }
    constructor(value:String) { data=Tokens.objects[value]?.toMutableMap() ?: error("invalid object") }
    fun put(k:String,v:Any?)=apply { data[k]=v }
    fun getString(k:String)=data[k] as String
    fun optString(k:String,d:String="")=data[k] as? String ?: d
    fun optBoolean(k:String,d:Boolean=false)=data[k] as? Boolean ?: d
    fun has(k:String)=data.containsKey(k)
    fun remove(k:String)=data.remove(k)
    override fun toString():String { val id=Tokens.id(); Tokens.objects[id]=data.toMap(); return id }
}
class JSONArray {
    private val values:MutableList<Any?>
    constructor() { values=mutableListOf() }
    constructor(value:String?) { values=if(value==null || value=="[]") mutableListOf() else Tokens.arrays[value]?.toMutableList() ?: error("invalid array") }
    fun put(v:Any?)=apply { values+=v }
    fun length()=values.size
    fun getJSONObject(i:Int)=values[i] as JSONObject
    override fun toString():String { val id=Tokens.id(); Tokens.arrays[id]=values.toList(); return id }
}
