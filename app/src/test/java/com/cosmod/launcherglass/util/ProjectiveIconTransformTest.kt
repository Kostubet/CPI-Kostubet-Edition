package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class ProjectiveIconTransformTest {
    private fun affine(scale:Float,x:Float,y:Float)=floatArrayOf(scale,0f,x,0f,scale,y,0f,0f,1f)
    private fun identity()=FloatArray(16).apply {this[0]=1f;this[5]=1f;this[10]=1f;this[15]=1f}
    private fun point(m:FloatArray,x:Float,y:Float):Pair<Float,Float>{
        val w=m[12]*x+m[13]*y+m[15]
        return (m[0]*x+m[1]*y+m[3])/w to (m[4]*x+m[5]*y+m[7])/w
    }
    @Test fun identicalNativeCoordinatesUseIdenticalPerspective(){
        val deformation=AppWindowDeformation(-.03f,.02f,.94f,1.03f,.48f,.51f)
        val warp=identity();deformation.writeMatrix(warp,0f,0f,1080f,2400f)
        val native=affine(.4f,180f,320f);val target=ProjectiveIconTransform()
        assertTrue(target.update(native,native,warp))
        warp.indices.forEach {assertEquals(warp[it],target.matrix[it],.0001f)}
    }
    @Test fun differentSourceSizesMeetAtTheSameScreenCoordinates(){
        val warp=identity();AppWindowDeformation(-.04f,.03f,.93f,1.05f,.47f,.52f).writeMatrix(warp,0f,0f,1080f,2400f)
        val app=affine(.5f,80f,120f);val icon=affine(.2f,220f,380f);val target=ProjectiveIconTransform()
        assertTrue(target.update(icon,app,warp))
        for(x in listOf(0f,100f,200f))for(y in listOf(0f,80f,160f)){
            val sourceX=(x*.2f+220f-80f)/.5f;val sourceY=(y*.2f+380f-120f)/.5f
            val expected=point(warp,sourceX,sourceY);val actual=point(target.matrix,x,y)
            assertEquals(expected.first*.5f+80f,actual.first*.2f+220f,.002f)
            assertEquals(expected.second*.5f+120f,actual.second*.2f+380f,.002f)
        }
    }
    @Test fun finalIdentityCannotDisplaceASmallIcon(){
        val target=ProjectiveIconTransform()
        assertTrue(target.update(affine(.018f,880f,2100f),affine(.09f,600f,1500f),identity()))
        identity().indices.forEach {assertEquals(identity()[it],target.matrix[it],.0001f)}
    }
    @Test fun refusesSingularNativeTransforms(){
        assertFalse(ProjectiveIconTransform().update(affine(0f,0f,0f),affine(1f,0f,0f),identity()))
    }
    @Test fun perspectiveKeepsAnimatedIconOnItsNativePath() {
        val warp = identity()
        AppWindowDeformation(-.04f,.03f,.93f,1.05f,.47f,.52f).writeMatrix(warp,0f,0f,1080f,2400f)
        val target = ProjectiveIconTransform()
        assertTrue(target.update(affine(.2f,220f,380f),affine(.5f,80f,120f),warp))
        assertTrue(ProjectiveIconTransform.center(target.matrix, 100f, 80f))
        val (x, y) = point(target.matrix, 100f, 80f)
        assertEquals(100f, x, .002f)
        assertEquals(80f, y, .002f)
    }
    @Test fun rejectsPlaneWhichWouldSendAnIconIntoAnotherCell(){
        val displaced=identity().apply {this[3]=200f}
        assertFalse(ProjectiveIconTransform.bounded(displaced,0f,0f,100f,100f))
    }
    @Test fun acceptsTiltWithinTheNativeIconFootprint(){
        val warp=identity()
        assertTrue(AppWindowDeformation(-.02f,.03f,.95f,1.04f,.49f,.51f).writeCenteredMatrix(warp,0f,0f,100f,100f))
        assertTrue(ProjectiveIconTransform.bounded(warp,0f,0f,100f,100f))
    }
    @Test fun rejectsCameraCrossingAndAcceptsFinalIdentity(){
        assertFalse(ProjectiveIconTransform.bounded(identity().apply {this[15]=.01f},0f,0f,100f,100f))
        assertTrue(ProjectiveIconTransform.bounded(identity(),0f,0f,100f,100f))
    }
}
