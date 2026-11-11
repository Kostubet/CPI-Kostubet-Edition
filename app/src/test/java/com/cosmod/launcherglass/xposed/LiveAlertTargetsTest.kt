package com.cosmod.launcherglass.xposed
import org.junit.Assert.*
import org.junit.Test
import com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView
import com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView
class LiveAlertTargetsTest {
 @Test fun expandedBackgroundIsSelected(){assertEquals("draw",LiveAlertTargets.drawMethod(CardBackgroundView::class.java)?.name)}
 @Test fun closedPillIsNeverSelected(){assertNull(LiveAlertTargets.drawMethod(CapsuleView::class.java));assertNull(LiveAlertTargets.drawMethod(Any::class.java))}
}
