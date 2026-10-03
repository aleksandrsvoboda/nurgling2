// Smooth freely propagating wave packets. Their sum forms two broad wake arms;
// compact Gaussian ends merge at turns without joining offset polylines.
// RG stores world-space slope; B stores broken foam on the leading crest.
float wake_hash(vec2 p) {
    vec3 q=fract(vec3(p.xyx)*vec3(.1031,.1030,.0973));
    q+=dot(q,q.yzx+33.33);
    return fract((q.x+q.y)*q.z);
}
float wake_noise(vec2 p) {
    vec2 cell=floor(p),f=fract(p);
    vec2 u=f*f*f*(f*(f*6.0-15.0)+10.0);
    return mix(mix(wake_hash(cell),wake_hash(cell+vec2(1,0)),u.x),
               mix(wake_hash(cell+vec2(0,1)),wake_hash(cell+vec2(1,1)),u.x),u.y);
}
vec4 wake_splat(vec2 p,vec4 data,vec2 direction,vec3 world) {
    float age=data.x,width=abs(data.y),energy=data.z,spread=data.w;
    float side=sign(data.y);
    float band=max(.55,width*.25)+age*.22;
    // Spatial emission spacing matches WaterWakes.DISTANCE_STEP (1.5 units).
    float packetLength=max(width*.8,3.0)+age*.45;
    // All overlapping packets use the same world-space pattern. Independent
    // sine phases per packet made the crest alternate in a checker pattern.
    float grain=wake_noise(world.xy*.43+vec2(17.3,4.1));
    float bend=(wake_noise(world.xy*.19)-.5)*.32;
    float q=p.x/band+bend;
    float crest=exp(-q*q*1.4);
    float inner=exp(-(q+1.8)*(q+1.8)*1.7);
    float envelope=exp(-p.y*p.y/(packetLength*packetLength));
    // Normalize overlap against source spacing instead of accumulating a bright
    // solid line. Longitudinal broadening also dissipates the old wave.
    float overlap=min(1.0,1.5/(1.77245*packetLength));
    float life=smoothstep(0.0,.12,age)*(1.0-smoothstep(1.1,4.8,age))/(1.0+age*.25);
    float resolved=1.0-smoothstep(1.0,3.5,fwidth(p.x)/band);
    float derivative=(-2.8*q*crest-.68*(q+1.8)*inner)/band;
    // Each packet belongs to one side of its source path. Gaussian ends must
    // not cross the centreline or extend through the apex into a forward X.
    float sideDistance=.8660254*(age*spread+p.x)+.5*side*p.y;
    float forward=.5*p.x-.8660254*side*p.y-1.5*age*spread;
    float wedge=smoothstep(0.0,band*.8,sideDistance)*(1.0-smoothstep(-band*.7,0.0,forward));
    float amplitude=energy*life*overlap*envelope*wedge;
    vec2 slope=direction*derivative*.24*amplitude*resolved;
    // A continuous crest with gentle aperiodic variation, never punched-out
    // islands. Keep the secondary crest softer than the leading edge.
    float foam=amplitude*(crest*1.10+inner*.06)*mix(.72,1.0,grain);
    return vec4(slope,foam,0);
}
