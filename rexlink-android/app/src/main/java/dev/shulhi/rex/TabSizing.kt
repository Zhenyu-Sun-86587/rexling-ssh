package dev.shulhi.rex

import kotlin.math.roundToInt

internal data class TabWidths(val active: Float,val inactive: Float)

/** Widths depend on space and tab count, never on changing task titles. Values are dp. */
internal fun tabWidths(available: Float,count: Int,hasActive: Boolean,density: Float=1f): TabWidths {
    if(count<=0) return TabWidths(0f,0f)
    // Compose rounds each item and gap separately. Allocate whole pixels first
    // so accumulated rounding cannot clip the last tab in an otherwise full strip.
    val viewport=(available.coerceAtLeast(0f)*density).roundToInt()
    val minimum=(48f*density).roundToInt()
    val inactiveCap=(120f*density).roundToInt()
    val space=viewport-(4f*density).roundToInt()*(count-1)
    if(!hasActive) return TabWidths(0f,(space/count).coerceIn(minimum,inactiveCap)/density)
    val preferred=viewport.coerceIn(minimum,(160f*density).roundToInt())
    if(count==1) return TabWidths(preferred/density,0f)
    // Keep a useful title on the active tab. Beyond this limit, allow scrolling
    // rather than reducing every tab to an indistinguishable sliver.
    val afterIcons=space-minimum*(count-1)
    val active=if(afterIcons>=(104f*density).roundToInt()) minOf(preferred,afterIcons) else preferred
    return TabWidths(active/density,((space-active)/(count-1)).coerceIn(minimum,inactiveCap)/density)
}
