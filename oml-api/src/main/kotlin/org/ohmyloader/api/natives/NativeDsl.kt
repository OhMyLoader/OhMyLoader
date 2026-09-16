package org.ohmyloader.api.natives

import java.lang.foreign.*
import java.lang.invoke.MethodHandle

/**
 * The FFM binding DSL: [loadLibrary] hands a mod a `SymbolLookup`, and these two extensions turn
 * a symbol into a callable [MethodHandle] in one line — the whole boilerplate of
 * `Linker.nativeLinker()` + `find(...)` + `FunctionDescriptor` + the not-found error message,
 * which every raw binding otherwise repeats. The returned handle binds the symbol's address
 * eagerly: a missing symbol fails at binding time with a [LinkageError] naming the symbol, not
 * later at first call inside a packet handler.
 */
fun SymbolLookup.downcall(name: String, returnType: MemoryLayout, vararg argTypes: MemoryLayout): MethodHandle =
    Linker.nativeLinker().downcallHandle(requireSymbol(name), FunctionDescriptor.of(returnType, *argTypes))

/** Void-returning variant of [downcall] — for procedures that return nothing. */
fun SymbolLookup.downcallVoid(name: String, vararg argTypes: MemoryLayout): MethodHandle =
    Linker.nativeLinker().downcallHandle(requireSymbol(name), FunctionDescriptor.ofVoid(*argTypes))

private fun SymbolLookup.requireSymbol(name: String): MemorySegment =
    find(name).orElseThrow { LinkageError("native symbol '$name' not found in library (lookup: $this)") }
