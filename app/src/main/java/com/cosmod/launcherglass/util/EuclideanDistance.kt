package com.cosmod.launcherglass.util

/** Exact squared Euclidean transform. Baked per contour, never evaluated on live frames. */
internal object EuclideanDistance {
    fun transform(values:FloatArray,width:Int,height:Int){
        require(width>0 && height>0 && values.size==width*height)
        val count=maxOf(width,height)
        val input=FloatArray(count);val output=FloatArray(count)
        val vertices=IntArray(count);val boundaries=DoubleArray(count+1)
        fun line(length:Int){
            var last=0;vertices[0]=0;boundaries[0]=Double.NEGATIVE_INFINITY;boundaries[1]=Double.POSITIVE_INFINITY
            for(q in 1 until length){
                var split:Double
                while(true){
                    val v=vertices[last]
                    split=((input[q].toDouble()+q.toDouble()*q)-(input[v].toDouble()+v.toDouble()*v))/(2.0*(q-v))
                    if(last==0 || split>boundaries[last])break
                    last--
                }
                vertices[++last]=q;boundaries[last]=split;boundaries[last+1]=Double.POSITIVE_INFINITY
            }
            last=0
            for(q in 0 until length){
                while(boundaries[last+1]<q)last++
                val d=q-vertices[last];output[q]=d.toFloat()*d+input[vertices[last]]
            }
        }
        for(y in 0 until height){
            for(x in 0 until width)input[x]=values[y*width+x]
            line(width)
            for(x in 0 until width)values[y*width+x]=output[x]
        }
        for(x in 0 until width){
            for(y in 0 until height)input[y]=values[y*width+x]
            line(height)
            for(y in 0 until height)values[y*width+x]=output[y]
        }
    }
}
