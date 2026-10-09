package org.ohmyloader.api26_3

import net.minecraft.world.entity.EquipmentSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The slot-name currency the equipment helpers are addressed by, pinned against the game's own enum: an
 * `EquipmentSlot` carries a name the game uses in its data, and a mod that spells it wrong has to be told so
 * rather than have the write land on some other slot.
 */
class EquipmentSlotNamesTest {

    @Test
    fun `every slot the game names resolves and round-trips`() {
        for (slot in EquipmentSlot.values()) {
            assertEquals(slot, equipmentSlotOf(slot.slotName), slot.slotName)
            assertEquals(slot, equipmentSlotOf(slot.slotName.uppercase()), "the lookup is by name, not by case")
        }
    }

    @Test
    fun `an unnamed slot answers null`() {
        assertNull(equipmentSlotOf("saddlebow"))
        assertNull(equipmentSlotOf(""))
    }
}
