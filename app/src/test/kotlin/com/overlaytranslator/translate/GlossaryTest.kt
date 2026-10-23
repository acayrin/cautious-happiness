package com.overlaytranslator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {

    @Test
    fun `empty glossary returns input unchanged`() {
        assertEquals("伊莎很強", Glossary.EMPTY.apply("伊莎很強"))
    }

    @Test
    fun `single term is replaced`() {
        val g = Glossary(mapOf("伊莎" to "Isa"))
        assertEquals("Isa 很強", g.apply("伊莎 很強"))
    }

    @Test
    fun `longest match wins`() {
        val g = Glossary(mapOf("伊莎" to "Isa", "伊莎贝尔" to "Isabel"))
        assertEquals("Isabel 很強", g.apply("伊莎贝尔 很強"))
    }

    @Test
    fun `overlapping terms all get replaced`() {
        val g = Glossary(mapOf("伊莎" to "Isa", "魔女" to "Witch"))
        assertEquals("Isa is a Witch", g.apply("伊莎 is a 魔女"))
    }

    @Test
    fun `blank keys and values are dropped`() {
        val g = Glossary(mapOf("  " to "x", "ok" to "  ", "伊莎" to "Isa"))
        assertEquals("Isa", g.apply("伊莎"))
    }

    @Test
    fun `unicode text survives round trip`() {
        val g = Glossary(mapOf("魔女兵器" to "Witch Weapon"))
        assertEquals("Witch Weapon 公式", g.apply("魔女兵器 公式"))
    }

    @Test
    fun `parse reads tab separated lines`() {
        val g = Glossary.parse(
            """
            # comment line
            伊莎	Isa
            魔女	Witch

            bad-line-no-tab
            ok	term
            """.trimIndent()
        )
        assertEquals("Isa", g.apply("伊莎"))
        assertEquals("Witch", g.apply("魔女"))
        assertEquals("term", g.apply("ok"))
        assertTrue(g.toMap().size == 3)
    }

    @Test
    fun `parse handles chinese tabs correctly`() {
        val g = Glossary.parse("魔女兵器	Witch Weapon")
        assertEquals("Witch Weapon", g.apply("魔女兵器"))
    }

    @Test
    fun `isEmpty reflects entries`() {
        assertTrue(Glossary.EMPTY.isEmpty())
        assertTrue(!Glossary(mapOf("a" to "b")).isEmpty())
    }
}
