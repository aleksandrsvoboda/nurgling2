vec2 water_project(vec3 p, vec4 pp, vec4 pr) {
    return (p.xy*pr.xy/(pp.z>.5?1.0:max(-p.z,.001))+pr.zw)*.5+.5;
}
vec3 water_view(vec2 uv, float depth, vec4 pp, vec4 pr) {
    return vec3((uv*2.0-1.0-pr.zw)/pr.xy*(pp.z>.5?1.0:depth),-depth);
}
float water_fresnel(float nv) {return .02037+.97963*pow(1.0-clamp(nv,0.0,1.0),5.0);}
// Preserve the water body at grazing angles instead of turning the whole river
// into the sky cube. This is the material's display-oriented reflection response.
float water_reflectance(float nv,float ocean) {
    float f=water_fresnel(nv),limit=mix(.16,.25,ocean);
    return f*limit/(limit+f);
}
vec3 water_transmission(float distance, float ocean) {
    // Absorption plus suspended sediment: retain the thin shoreline, but obscure
    // the bed at the game's actual river depths (6 shallow / 30 deep units).
    return exp(-mix(vec3(.17,.10,.075),vec3(.105,.060,.043),ocean)*max(distance,0.0));
}
// View-independent, bounded fill light keeps the existing wave normals readable
// outside the sun's reflection cone. This is an artistic scattering response;
// it does not change the wave geometry, spectrum or animation.
vec3 water_body(vec3 scatter,vec3 N,vec3 L,vec3 skylight) {
    vec2 key=L.xy/max(length(L.xy),.2);
    float facing=dot(N.xy,key);
    float relief=tanh(facing*7.0);
    return scatter*(1.0+relief*.32)+skylight*vec3(.018,.024,.028)*max(relief,0.0);
}
vec3 water_glint(vec3 N, vec3 V, vec3 L, vec3 up, vec3 sun, float ocean, float rain) {
    float nv=max(dot(N,V),.01);
    vec3 H=(V+L)/max(length(V+L),.0001);
    float nl=max(dot(N,L),0.0),nh=max(dot(N,H),0.0),vh=max(dot(V,H),0.0);
    vec3 nx=dFdx(N),ny=dFdy(N);
    float variance=max(dot(nx,nx),dot(ny,ny));
    // Resolved ripples carry a tighter specular lobe; normal variance broadens
    // it with distance so subpixel facets do not become flashing white points.
    float alpha2=clamp(pow(mix(.23,.24,ocean)+rain*.04,4.0)+variance*2.0,.0028,.3);
    float denominator=nh*nh*(alpha2-1.0)+1.0;
    float D=alpha2/(3.14159265*denominator*denominator);
    float k=sqrt(alpha2)*.5;
    float Gv=nv/(nv*(1.0-k)+k),Gl=nl/(nl*(1.0-k)+k);
    vec3 specular=max(sun,vec3(0))*D*Gv*Gl*water_fresnel(vh)/(4.0*nv+.001);
    // Smooth display-oriented shoulder on the direct sun/moon contribution.
    // Preserve hue and the lobe's gradient; clipping at 2 made entire crests
    // white before grading (and outright saturated the non-HDR framebuffer).
    float peak=max(specular.r,max(specular.g,specular.b));
    // A facet crossing the view/light horizon must fade, not jump from a
    // compressed bright lobe to zero in one pixel.
    float horizon=smoothstep(0.0,.18,dot(N,V))*smoothstep(0.0,.18,dot(N,L));
    float limit=mix(.26,.30,ocean);
    // Keep the bright response on resolved tilted facets. The old response
    // saturated almost every facet near the mirror angle into silver foil.
    // A faint broad sheen remains; local crests can still sparkle brightly.
    float facet=smoothstep(.035,.14,length(N-up));
    float sparkle=facet*facet/(1.0+variance*80.0);
    return specular*(limit/(limit+peak))*horizon*1.30*(.07+.93*sparkle);
}
// Broad sky reflection gives selected positive crests a readable sheen away
// from the sun's narrow lobe. Deliberately art-directed, bounded and tied to
// the travelling height field; it adds neither foam nor a second wave pattern.
vec3 water_crest_glint(float crest,vec3 N,vec3 V,vec3 up,vec3 skylight) {
    float skyFacing=smoothstep(-.1,.65,dot(reflect(-V,N),up));
    return max(skylight,vec3(0))*.16*crest*(.65+.35*skyFacing);
}
// World-space impacts: neighbouring cells are evaluated too, so a ring is never
// cut at a grid/map-section edge. Each birth changes its centre and phase. Rings
// live for 1.6 seconds; the envelope is zero before the next random birth.
vec3 water_rain_rings(vec2 p,float t,float rain,float footprint) {
    vec3 rings=vec3(0);
    float resolved=1.0-smoothstep(.12,.70,footprint);
    if(rain<.001 || resolved<=0.0) return rings;
    vec2 cell=floor(p/4.5);
    for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
        vec2 id=cell+vec2(x,y);
        float period=mix(1.9,3.0,water_hash(id+17.8));
        float clock=t/period+water_hash(id+61.3);
        float birth=floor(clock),age=fract(clock)*period/1.6;
        if(age>=1.0) continue;
        vec2 seed=id+vec2(birth*13.7,birth*7.1);
        float probability=min(.90,rain*.36);
        float emission=smoothstep(0.0,.12,probability-water_hash(seed+41.6));
        vec2 center=(id+.12+.76*vec2(water_hash(seed+3.2),water_hash(seed+29.1)))*4.5;
        vec2 delta=p-center;
        float distance=length(delta),d=distance-(.10+age*2.0);
        float packet=exp(-d*d/.07)*(1.0-smoothstep(.4,.6,abs(d)));
        float envelope=emission*smoothstep(0.0,.10,age)*(1.0-smoothstep(.70,1.0,age))*exp(-age*2.0)*resolved;
        float slope=packet*(18.0*cos(d*18.0)-2.0*d/.07*sin(d*18.0))*.008*envelope;
        rings.xy+=delta/max(distance,.001)*slope;
        rings.z+=packet*max(cos(d*18.0),0.0)*envelope;
    }
    return rings;
}
vec4 water_color(vec3 rest, vec3 ep, vec4 data, float t, mat4 camera,
                 sampler2D scene, sampler2D depths, vec4 pp, vec4 pr, samplerCube sky,
                 vec3 L, vec3 sun, vec3 skylight, vec2 rainfall, float reflections, sampler2D wakes, vec2 waveStrength) {
    vec2 uv=water_project(ep,pp,pr);
    vec2 pixel=1.0/vec2(textureSize(scene,0));
    float surfaceDepth=-ep.z;
    float backgroundDepth=texture(depths,uv).r;
    // This also protects foliage, people and boats in front of the water.
    if(backgroundDepth<surfaceDepth-.025) discard;
    float footprint=max(length(dFdx(rest.xy)),length(dFdy(rest.xy)));
    vec3 rippleNormal; float crest;
    vec3 worldN=water_normals(rest.xy,data,t,footprint,waveStrength,rippleNormal,crest);
    float rain=clamp(rainfall.x,0.0,1.0);
    vec3 rings=vec3(0);
    if(rainfall.y>.5) rings=water_rain_rings(rest.xy,t,rainfall.x,footprint)*smoothstep(0.0,1.0,data.x);
    worldN=normalize(worldN-vec3(rings.xy,0));
    vec3 wake=texture(wakes,uv).rgb;
    worldN=normalize(worldN-vec3(clamp(wake.xy,vec2(-.22),vec2(.22)),0));
    vec3 N=normalize(mat3(camera)*worldN);
    vec3 up=normalize(mat3(camera)*vec3(0,0,1));
    vec3 V=pp.z>.5?vec3(0,0,1):normalize(-ep);
    float nv=max(dot(N,V),.01);
    float path=max(0.0,backgroundDepth-surfaceDepth);
    if(pp.z<.5) path*=length(ep)/max(surfaceDepth,.001);
    path=min(path,120.0);
    // A normal-induced refracted ray, relative to refraction through a flat
    // surface. Limit distortion to several pixels and reject foreground hits.
    vec3 ray=refract(-V,N,1.0/1.333);
    vec3 flatRay=refract(-V,up,1.0/1.333);
    vec2 bent=water_project(ep+(ray-flatRay)*min(path,12.0),pp,pr)-uv;
    float bendPixels=length(bent/pixel);
    bent*=min(1.0,5.0/max(bendPixels,.001));
    vec2 refracted=clamp(uv+bent,pixel*.5,vec2(1)-pixel*.5);
    float refractedDepth=texture(depths,refracted).r;
    // Conservative around silhouettes, so a bank cannot bleed into its water.
    if(refractedDepth<surfaceDepth+.08 || abs(refractedDepth-backgroundDepth)>max(2.0,path*.5)) refracted=uv;
    vec3 trans=water_transmission(path,data.y);
    // Keep the familiar palette: blue inland water, turquoise ocean water.
    vec3 scatter=mix(vec3(.050,.110,.190),vec3(.020,.220,.180),data.y)*skylight;
    scatter=water_body(scatter,worldN,transpose(mat3(camera))*L,skylight);
    vec3 below=texture(scene,refracted).rgb*trans+scatter*(1.0-trans);
    // A restrained sky-lit crest remains readable with reflections disabled.
    below+=skylight*.42*rings.z*smoothstep(0.0,.28,path);
    vec3 foamColor=vec3(.50,.62,.66)*skylight;
    // Smooth overlap compression preserves crest variation instead of clipping
    // the stronger continuous foam into a flat, uniformly bright stripe.
    float wakeFoam=.55*(1.0-exp(-max(wake.z,0.0)*1.5))*smoothstep(0.0,.35,path);
    // Skip the reflection march and glints while retaining refraction and waves.
    if(reflections<.5) return vec4(max(mix(below,foamColor,wakeFoam),vec3(0)),1.0);
    vec3 R=reflect(-V,N);
    vec3 worldR=transpose(mat3(camera))*R;
    vec3 reflection=texture(sky,worldR).rgb*skylight;
    // Bounded current-frame SSR, only above-water intersections. Environment
    // fallback at screen edges/misses; no history or accumulation noise.
    if(dot(R,up)>.06) {
        float travel=.7, previous=-1.0, previousTravel=0.0;
        for(int i=0;i<20;i++) {
            vec3 p=ep+R*travel;
            if(p.z>=-.1) break;
            vec2 tc=water_project(p,pp,pr);
            if(any(lessThan(tc,pixel)) || any(greaterThan(tc,vec2(1)-pixel))) break;
            float sd=texture(depths,tc).r;
            float difference=-p.z-sd;
            if(i>0 && difference>=0.0 && previous<0.0 && difference<2.0+travel*.12) {
                float low=previousTravel,high=travel;
                for(int j=0;j<4;j++) {
                    float middle=(low+high)*.5;
                    vec3 q=ep+R*middle; vec2 quv=water_project(q,pp,pr);
                    if(-q.z>texture(depths,quv).r) high=middle;else low=middle;
                }
                vec3 hit=ep+R*((low+high)*.5); tc=water_project(hit,pp,pr);
                float hitDepth=texture(depths,tc).r;
                vec3 actual=water_view(tc,hitDepth,pp,pr);
                float above=dot(actual-ep,up);
                float edge=min(min(tc.x,tc.y),min(1.0-tc.x,1.0-tc.y));
                float confidence=smoothstep(0.0,.07,edge)*smoothstep(.15,.8,above);
                confidence*=1.0-smoothstep(100.0,180.0,travel);
                reflection=mix(reflection,texture(scene,tc).rgb,confidence*.85);
                break;
            }
            previous=difference;previousTravel=travel;travel=travel*1.23+.7;
        }
    }
    float fresnel=water_reflectance(nv,data.y);
    vec3 specular=water_glint(N,V,L,up,sun,data.y,rain);
    specular+=water_crest_glint(crest,N,V,up,skylight);
    // Close to a bank reflection fades with water thickness, joining dry land.
    float coverage=smoothstep(0.0,.28,path);
    vec3 result=mix(below,reflection,fresnel*coverage)+specular*coverage;
    result=mix(result,foamColor,wakeFoam);
    return vec4(max(result,vec3(0)),1.0);
}
