package com.caio.navi

import org.json.JSONArray
import org.json.JSONObject

/**
 * Uma ação que o cérebro (Gemini) pode chamar.
 * [confirm] devolve a pergunta de confirmação (ou null se não precisa confirmar).
 */
class Tool(
    val name: String,
    val description: String,
    val params: JSONObject? = null,
    val confirm: ((JSONObject) -> String?)? = null,
    /** Confirma mesmo se o usuário desligou as confirmações (ex.: PowerShell, desligar o PC). */
    val alwaysConfirm: Boolean = false,
    val run: suspend (JSONObject) -> JSONObject,
) {
    fun declaration(): JSONObject {
        val d = JSONObject().put("name", name).put("description", description)
        if (params != null) d.put("parameters", params)
        return d
    }
}

/** Ajudantes para montar o esquema dos parâmetros. */
object Schema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        for ((k, v) in props) p.put(k, v)
        val o = JSONObject().put("type", "object").put("properties", p)
        if (required.isNotEmpty()) o.put("required", JSONArray(required))
        return o
    }

    fun str(desc: String, enum: List<String>? = null): JSONObject {
        val o = JSONObject().put("type", "string").put("description", desc)
        if (enum != null) o.put("enum", JSONArray(enum))
        return o
    }

    fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    fun strList(desc: String) = JSONObject()
        .put("type", "array").put("description", desc)
        .put("items", JSONObject().put("type", "string"))
}

fun ok(msg: String = "feito") = JSONObject().put("status", "ok").put("resultado", msg)
fun fail(msg: String) = JSONObject().put("status", "erro").put("erro", msg)
