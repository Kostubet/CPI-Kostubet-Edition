package com.cosmod.launcherglass.util

/** I^-1 A P A^-1 I: the same world-space plane, expressed in each surface's local units. */
internal class ProjectiveIconTransform {
    val matrix=FloatArray(16)
    private val inverseApp=DoubleArray(9);private val inverseIcon=DoubleArray(9)
    private val app=DoubleArray(9);private val icon=DoubleArray(9);private val warp=DoubleArray(9)
    private val first=DoubleArray(9);private val world=DoubleArray(9);private val local=DoubleArray(9);private val output=DoubleArray(9)
    fun update(iconMatrix:FloatArray,appMatrix:FloatArray,perspective:FloatArray):Boolean {
        if(iconMatrix.size!=9 || appMatrix.size!=9 || perspective.size!=16)return false
        for(i in 0..8){app[i]=appMatrix[i].toDouble();icon[i]=iconMatrix[i].toDouble()}
        if(!inverse(app,inverseApp) || !inverse(icon,inverseIcon))return false
        for(i in 0..8)warp[i]=perspective[indices[i]].toDouble()
        multiply(app,warp,first);multiply(first,inverseApp,world)
        multiply(inverseIcon,world,local);multiply(local,icon,output)
        if(output.any {!it.isFinite()})return false
        matrix.fill(0f);matrix[10]=1f
        for(i in 0..8)matrix[indices[i]]=output[i].toFloat()
        return true
    }
    private fun multiply(a:DoubleArray,b:DoubleArray,out:DoubleArray){
        for(row in 0..2)for(column in 0..2)out[row*3+column]=a[row*3]*b[column]+a[row*3+1]*b[3+column]+a[row*3+2]*b[6+column]
    }
    private fun inverse(m:DoubleArray,out:DoubleArray):Boolean {
        val a=m[0];val b=m[1];val c=m[2];val d=m[3];val e=m[4];val f=m[5];val g=m[6];val h=m[7];val i=m[8]
        val aa=e*i-f*h;val dd=f*g-d*i;val gg=d*h-e*g
        val determinant=a*aa+b*dd+c*gg
        if(!determinant.isFinite() || kotlin.math.abs(determinant)<1e-12)return false
        out[0]=aa/determinant;out[1]=(c*h-b*i)/determinant;out[2]=(b*f-c*e)/determinant
        out[3]=dd/determinant;out[4]=(a*i-c*g)/determinant;out[5]=(c*d-a*f)/determinant
        out[6]=gg/determinant;out[7]=(b*g-a*h)/determinant;out[8]=(a*e-b*d)/determinant
        return out.all {it.isFinite()}
    }
    companion object {
        private val indices=intArrayOf(0,1,3,4,5,7,12,13,15)
        /** Preserve the launcher's icon position while keeping the app plane's perspective. */
        fun center(matrix: FloatArray, x: Float, y: Float): Boolean {
            val w = matrix[12] * x + matrix[13] * y + matrix[15]
            if (!w.isFinite() || w < .15f) return false
            val dx = (matrix[0] * x + matrix[1] * y + matrix[3]) / w - x
            val dy = (matrix[4] * x + matrix[5] * y + matrix[7]) / w - y
            for (i in 0..3) {
                matrix[i] -= dx * matrix[12 + i]
                matrix[4 + i] -= dy * matrix[12 + i]
            }
            return matrix.all(Float::isFinite)
        }

        /** An icon must not extrapolate a distant app plane into another workspace cell. */
        fun bounded(matrix:FloatArray,left:Float,top:Float,width:Float,height:Float):Boolean {
            if(matrix.size!=16 || width<=0f || height<=0f || matrix.any {!it.isFinite()})return false
            fun valid(x:Float,y:Float):Boolean {
                val w=matrix[12]*x+matrix[13]*y+matrix[15]
                if(w<.15f)return false
                val px=(matrix[0]*x+matrix[1]*y+matrix[3])/w
                val py=(matrix[4]*x+matrix[5]*y+matrix[7])/w
                return px.isFinite() && py.isFinite() &&
                    kotlin.math.abs(px-x)<=width*.6f && kotlin.math.abs(py-y)<=height*.6f
            }
            return valid(left,top) && valid(left+width,top) && valid(left,top+height) &&
                valid(left+width,top+height) && valid(left+width*.5f,top+height*.5f)
        }
    }
}
