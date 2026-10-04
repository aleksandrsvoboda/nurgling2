// Multiscale height field with analytical wave derivatives.
// Units are Haven world units. Frequencies follow omega^2 = g*k; no noise-height
// scrolling. Shore attenuation anchors geometry where the bed meets the bank.
float water_hash(vec2 p) {
    vec3 q=fract(vec3(p.xyx)*vec3(.1031,.1030,.0973));
    q+=dot(q,q.yzx+33.33);
    return fract((q.x+q.y)*q.z);
}
// Smooth value and its exact spatial derivative. This modulates travelling wave
// packets, never the surface height directly; no boiling noise or tile resets.
vec3 water_noise(vec2 p) {
    vec2 cell=floor(p),f=fract(p);
    vec2 u=f*f*f*(f*(f*6.0-15.0)+10.0);
    vec2 du=30.0*f*f*(f*(f-2.0)+1.0);
    float a=water_hash(cell),b=water_hash(cell+vec2(1,0));
    float c=water_hash(cell+vec2(0,1)),d=water_hash(cell+vec2(1,1));
    return vec3(mix(mix(a,b,u.x),mix(c,d,u.x),u.y),
                mix(b-a,d-c,u.y)*du.x,mix(c-a,d-b,u.x)*du.y);
}
void water_packet(vec2 p, vec2 direction, float k, float omega, float t, float seed,
                  out float phase, out vec2 gradient, out float envelope, out vec2 amplitudeGradient) {
    vec2 side=vec2(-direction.y,direction.x);
    // Different packet sizes and two bend scales break up long parallel crests.
    vec2 packetScale=vec2(mix(.18,.30,water_hash(vec2(seed,8.1))),
                          mix(.25,.40,water_hash(vec2(seed,21.7))));
    vec2 scale=k*packetScale;
    vec2 q=vec2(dot(p,direction),dot(p,side))*scale;
    // Deep-water group velocity is half the crest velocity. Each component has
    // its own envelope, so crests emerge and fade instead of spanning the river.
    q.x-=omega*t*packetScale.x*.5;
    q+=vec2(seed*17.13,seed*9.71);
    vec3 strength=water_noise(q),bend=water_noise(q*.73+vec2(5.2,13.9));
    vec3 bendDetail=water_noise(q*1.61+vec2(23.8,7.4));
    vec2 strengthGradient=(direction*strength.y*scale.x+side*strength.z*scale.y);
    vec2 bendGradient=(direction*bend.y*scale.x+side*bend.z*scale.y)*.73;
    vec2 detailGradient=(direction*bendDetail.y*scale.x+side*bendDetail.z*scale.y)*1.61;
    phase=k*dot(p,direction)-omega*t+seed*2.37+(bend.x-.5)*3.8+(bendDetail.x-.5)*1.3;
    gradient=k*direction+bendGradient*3.8+detailGradient*1.3;
    envelope=.18+1.35*strength.x*strength.x;
    amplitudeGradient=2.7*strength.x*strengthGradient;
}
void water_wave(int i, float ocean, out vec2 direction, out float k, out float a, out float omega) {
    float angle = float(i)*1.79 + .43 + (water_hash(vec2(float(i),4.9))-.5)*.8;
    // Rivers have crossing ripples; ocean swell retains a prevailing direction.
    vec2 river=vec2(cos(angle),sin(angle));
    vec2 oceanDirection=normalize(vec2(cos(angle)*.85+.65,sin(angle)*.85+.24));
    direction=normalize(mix(river,oceanDirection,ocean));
    float spread=i==0?1.0:mix(.85,1.15,water_hash(vec2(float(i),12.3)));
    float wavelength = mix(21.0,44.0,ocean)*pow(mix(.73,.61,ocean),float(i))*spread;
    k = 6.2831853/wavelength;
    // Broad travelling waves remain present even between the wind-ripple groups.
    // Scale height with length so smaller waves do not become twice as steep.
    a = mix(.16,.55,ocean)*pow(.51,float(i))*spread;
    omega = sqrt(29.43*k);
}
// Wind disturbs patches, not every square metre equally. Broad, slowly moving
// activity regions leave genuinely quiet river water between short ripple groups.
float water_activity(vec2 p,float t) {
    float broad=water_noise(p*.027+vec2(-t*.008,t*.003)+vec2(19.4,3.7)).x;
    float detail=water_noise(p*.061+vec2(t*.004,-t*.006)+vec2(4.8,31.2)).x;
    // Continuous energy distribution, not a near-binary calm/rough mask.
    return .15+.70*(broad*.72+detail*.28);
}
vec4 water_position(vec4 p, vec4 data, float t, float strength) {
    if(strength <= 0.0) return p;
    float shore = smoothstep(0.0,5.0,data.x);
    vec3 displacement = vec3(0.0);
    for(int i=0;i<4;i++) {
        vec2 d; float k,a,w; water_wave(i,data.y,d,k,a,w);
        float phase,envelope;vec2 gradient,amplitudeGradient;
        water_packet(p.xy,d,k,w,t,float(i),phase,gradient,envelope,amplitudeGradient);
        a*=envelope;
        // Keep the shared map-cut footprint fixed. Lateral Gerstner motion is
        // unsuitable for separately resolved terrain sections and shore edges.
        displacement.z += a*sin(phase);
    }
    return vec4(p.xyz+displacement*shore*strength,p.w);
}
vec4 water_position(vec4 p, vec4 data, float t) {
    return water_position(p,data,t,1.0);
}
// Return derivatives of the displaced surface. Separate the short waves from
// geometry, and attenuate them analytically at their pixel footprint.
vec3 water_normals(vec2 p, vec4 data, float t, float footprint, vec2 strength, out vec3 rippleNormal, out float crest) {
    if(max(strength.x,strength.y) <= 0.0) {
        rippleNormal=vec3(0,0,1); crest=0.0;
        return vec3(0,0,1);
    }
    float shore=smoothstep(0.0,5.0,data.x);
    float crestRidges=0.0;
    vec3 dx=vec3(1,0,0),dy=vec3(0,1,0);
    for(int i=0;i<4;i++) {
        vec2 d; float k,a,w; water_wave(i,data.y,d,k,a,w);
        float phase,envelope;vec2 gradient,amplitudeGradient;
        water_packet(p,d,k,w,t,float(i),phase,gradient,envelope,amplitudeGradient);
        a*=strength.x*shore*(1.0-smoothstep(.65,2.8,length(gradient)*footprint));
        // Narrow tops of the actual travelling waves, broken into packets by
        // their existing amplitude envelope. A threshold on total height alone
        // creates round bright blobs where crossing waves add together.
        float ridge=smoothstep(.90,.995,sin(phase))*smoothstep(.85,1.40,envelope);
        crestRidges+=ridge*pow(.62,float(i))*(1.0-smoothstep(.65,2.8,length(gradient)*footprint));
        vec2 vertical=a*(amplitudeGradient*sin(phase)+envelope*cos(phase)*gradient);
        dx.z+=vertical.x;
        dy.z+=vertical.y;
    }
    vec2 flow=data.zw;
    flow*=min(1.0,3.0/max(length(flow),.001));
    // Two staggered advected phases: flow never stretches the surface forever,
    // and its reset occurs at zero weight (the useful part of newgame's water).
    float phase=fract(t/7.0), other=fract(phase+.5), blend=abs(phase-.5)*2.0;
    vec2 slope=vec2(0);
    // Wind ripples settle over the shallow bed before reaching the waterline.
    // Depth is shared/interpolated across map sections; do not use view depth
    // or a per-tile switch. The travelling geometry waves keep their old shape.
    float rippleShore=smoothstep(0.0,12.0,data.x);
    for(int i=0;i<5;i++) {
        // Log-spaced intermediate scales bridge the geometry waves and fine
        // ripples. Each band has its own softly varying activity, so all detail
        // cannot switch on together at a visible boundary.
        float fi=float(i), wavelength=10.5*pow(.68,fi);
        float activity=water_activity(p*(.75+fi*.11)+vec2(fi*23.7,fi*11.3),t);
        float ripples=mix(.35+.65*activity,.48+.52*activity,data.y);
        float k=6.2831853/wavelength;
        vec2 d=normalize(vec2(cos(fi*2.19+.2),sin(fi*2.19+.2)));
        float a,b,ea,eb;vec2 ga,gb,aa,ab;
        water_packet(p-flow*phase*7.0,d,k,sqrt(29.43*k),t,fi+7.0,a,ga,ea,aa);
        water_packet(p-flow*other*7.0,d,k,sqrt(29.43*k),t,fi+7.0,b,gb,eb,ab);
        float resolved=1.0-smoothstep(.65,2.8,max(length(ga),length(gb))*footprint);
        vec2 sa=aa*sin(a)+ea*cos(a)*ga,sb=ab*sin(b)+eb*cos(b)*gb;
        slope+=mix(sa,sb,blend)*(mix(.044,.048,data.y)/k)*resolved*rippleShore*ripples*strength.y;
        // Only the two resolved middle bands contribute smaller crestlets;
        // lighting all five fine bands recreates a dense worm-like pattern.
        if(i<2) {
            float ra=smoothstep(.90,.995,sin(a))*smoothstep(.85,1.40,ea);
            float rb=smoothstep(.90,.995,sin(b))*smoothstep(.85,1.40,eb);
            crestRidges+=mix(ra,rb,blend)*(.28-.10*fi)*resolved*ripples;
        }
    }
    crest=(1.0-exp(-crestRidges*1.5))*smoothstep(0.0,12.0,data.x);
    crest*=min(strength.x,strength.y)*(1.0-smoothstep(.65,1.8,footprint));
    vec3 n=normalize(cross(dx,dy));
    rippleNormal=normalize(vec3(-slope,1));
    return normalize(n-vec3(slope,0));
}
vec3 water_normals(vec2 p, vec4 data, float t, float footprint, vec2 strength, out vec3 rippleNormal) {
    float crest;
    return water_normals(p,data,t,footprint,strength,rippleNormal,crest);
}
vec3 water_normals(vec2 p, vec4 data, float t, float footprint, out vec3 rippleNormal) {
    return water_normals(p,data,t,footprint,vec2(1),rippleNormal);
}
vec3 water_normal(vec2 p, vec4 data, float t, float footprint) {
    vec3 rippleNormal;
    return water_normals(p,data,t,footprint,rippleNormal);
}
