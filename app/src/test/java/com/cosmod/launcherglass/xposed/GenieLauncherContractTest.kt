package com.cosmod.launcherglass.xposed

import android.graphics.Matrix
import android.graphics.RectF
import android.view.SurfaceControl
import org.junit.Assert.assertEquals
import org.junit.Test

class GenieLauncherContractTest {
    class MovedParams
    @Suppress("UNUSED_PARAMETER")
    class FlattenedHelper {
        fun renamed(params:MovedParams,targets:Array<RemappedHelper.Target>,mode:Int,transaction:RenamedTransaction,
            record:RemappedHelper.Record,app:RectF,rotate:Boolean,icon:RectF,target:RemappedHelper.Target){}
    }
    @Test fun resolvesParamsThatR8MovedOutOfTheirOriginalOwner(){
        assertEquals(MovedParams::class.java,GenieLauncherContract.window(FlattenedHelper::class.java).params)
    }
    @Suppress("UNUSED_PARAMETER")
    class RemappedHelper {
        class Params
        class Target
        class Record

        fun renamed(params: Params, targets: Array<Target>, mode: Int, transaction: RenamedTransaction,
                    record: Record, app: RectF, rotate: Boolean, icon: RectF, target: Target) {}
    }

    @Suppress("UNUSED_PARAMETER")
    class RenamedTransaction {
        fun renamedFactory(surface: SurfaceControl): DifferentBuilder = DifferentBuilder(this, surface)

        class DifferentBuilder(
            @JvmField val parent: RenamedTransaction,
            @JvmField val control: SurfaceControl,
        ) {
            fun renamedMatrix(value: Matrix): DifferentBuilder = this
            fun renamedPerspective(value: FloatArray) {}
        }
    }

    @Suppress("UNUSED_PARAMETER")
    class RenamedIcon {
        enum class Rotation { NONE, RIGHT }
        fun renamedUpdate(params: RemappedHelper.Params, clip: Boolean, rect: RectF, surface: SurfaceControl,
                          transaction: RenamedTransaction, opening: Boolean, matrix: Matrix, rotation: Rotation) {}
    }

    @Suppress("UNUSED_PARAMETER")
    class ModifiedTransaction {
        fun factory(surface: SurfaceControl): Builder = Builder(this, surface)
        class Builder(@JvmField val parent: ModifiedTransaction, @JvmField val surface: SurfaceControl) {
            fun i(value: Matrix): Builder = this
            fun j(value: FloatArray) {}
            fun customMatrixBeforeTilt(value: Matrix): Builder = this
            fun customSetTilt(value: FloatArray) {}
        }
    }

    @Test
    fun resolvesTheTransactionBuilderAndRecordWithoutObfuscatedClassNames() {
        val result = GenieLauncherContract.window(RemappedHelper::class.java)
        assertEquals(RenamedTransaction::class.java, result.surface.transaction)
        assertEquals(RenamedTransaction.DifferentBuilder::class.java, result.surface.builder)
        assertEquals("renamedMatrix", result.surface.matrix.name)
        assertEquals("renamedPerspective", result.surface.perspective.name)
        assertEquals(RemappedHelper.Target::class.java, result.target)
        assertEquals(RemappedHelper.Record::class.java, result.record)
    }

    @Test
    fun resolvesTheRenamedIconUpdaterFromTheSameTransactionTypes() {
        val window = GenieLauncherContract.window(RemappedHelper::class.java)
        val icon = GenieLauncherContract.icon(RenamedIcon::class.java, window.params, window.surface)
        assertEquals("renamedUpdate", icon?.update?.name)
    }

    @Test
    fun retainsTheNativeMethodsWhenAModifiedLauncherAddsTiltWrappers() {
        val surface = GenieLauncherContract.surface(ModifiedTransaction::class.java)
        assertEquals("i", surface.matrix.name)
        assertEquals("j", surface.perspective.name)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsAnUnrelatedTransactionInsteadOfHookingArbitraryMethods() {
        GenieLauncherContract.surface(String::class.java)
    }
}
