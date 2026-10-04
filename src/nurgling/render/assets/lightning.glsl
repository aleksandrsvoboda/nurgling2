vec4 lightning_color(vec4 shape,vec3 ep,float age,sampler2D depths,vec4 pp,vec4 pr,vec4 timing) {
    if(age<0.0 || age>=.90) discard;
    vec2 uv=(ep.xy*pr.xy/(pp.z>.5?1.0:max(-ep.z,.001))+pr.zw)*.5+.5;
    float d=texture(depths,uv).r*2.0-1.0;
    float scene=pp.z>.5?-(d-pp.y)/pp.x:pp.y/(d+pp.x);
    if(scene<-ep.z-.03) discard;
    float t=age/timing.x;
    float strike=exp(-pow((t-.07)/.027,2.0));
    float pulse=lightning_pulse(t,timing);
    if(shape.z<0.0) {
        // Confined hot contact and cool corona, in addition to the scene flash.
        float r=length(shape.xy),edge=1.0-smoothstep(.65,1.0,r);
        float hot=exp(-r*r*130.0),halo=exp(-r*r*7.0);
        float rays=exp(-abs(shape.x)*42.0)*exp(-abs(shape.y)*7.0)
                  +exp(-abs(shape.y)*42.0)*exp(-abs(shape.x)*7.0);
        float landed=smoothstep(.04,.06,t);
        return vec4((vec3(.85,.94,1)*hot*3.2+vec3(.22,.40,1)*halo*.75+vec3(.55,.72,1)*rays*.32)*edge*pulse*landed,0);
    }
    // Persistent leader channel; branches respond differently to return strokes.
    float leader=1.0-smoothstep(t/.05-.04,t/.05+.04,shape.z);
    float branch=step(.5,shape.w);
    float phase=fract(sin(shape.w*13.17+timing.w)*43758.5453);
    float branchPulse=mix(1.0,.40+.60*phase+strike*.35,branch);
    float branchFade=mix(1.0,exp(-t*(1.0+phase*3.0)),branch);
    float x=abs(shape.x),aa=max(fwidth(shape.x),.002);
    float halfCore=.033;
    float core=(clamp(halfCore-shape.x+aa*.5,0.0,aa)-clamp(-halfCore-shape.x+aa*.5,0.0,aa))/aa;
    float plasma=exp(-x*x*160.0);
    float halo=exp(-x*x*8.0)*(1.0-smoothstep(.65,1.0,x));
    // Smooth current variation avoids frame-random sparkling noise.
    float current=.91+.09*sin(shape.z*53.0-t*75.0+timing.w);
    vec3 radiance=vec3(.88,.95,1.0)*core*5.2+vec3(.42,.60,1.0)*plasma*.95+vec3(.17,.28,.65)*halo*.40;
    return vec4(radiance*shape.y*pulse*leader*branchPulse*branchFade*current,0);
}
