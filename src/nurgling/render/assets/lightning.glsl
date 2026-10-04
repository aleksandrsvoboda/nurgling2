vec4 lightning_color(vec3 shape,vec3 ep,float age,sampler2D depths,vec4 pp,vec4 pr) {
    if(age<0.0 || age>=.68) discard;
    vec2 uv=(ep.xy*pr.xy/(pp.z>.5?1.0:max(-ep.z,.001))+pr.zw)*.5+.5;
    float d=texture(depths,uv).r*2.0-1.0;
    float scene=pp.z>.5?-(d-pp.y)/pp.x:pp.y/(d+pp.x);
    if(scene<-ep.z-.03) discard;
    // Fast descending leader, then several return strokes through the SAME
    // branching channel. No frame-random topology or full-screen colour flash.
    float leader=1.0-smoothstep(age/.055-.06,age/.055+.06,shape.z);
    float pulse=exp(-age*8.0)*.42;
    pulse+=exp(-pow((age-.07)/.035,2.0))*1.1;
    pulse+=exp(-pow((age-.23)/.042,2.0))*.8;
    pulse+=exp(-pow((age-.40)/.055,2.0))*.45;
    pulse*=1.0-smoothstep(.50,.68,age);
    float x=abs(shape.x),aa=max(fwidth(shape.x),.005);
    float core=(clamp(.07-shape.x+aa*.5,0.0,aa)-clamp(-.07-shape.x+aa*.5,0.0,aa))/aa;
    float glow=exp(-x*x*6.0)*(1.0-smoothstep(.65,1.0,x));
    vec3 radiance=vec3(.86,.92,1.0)*core*5.0+vec3(.26,.38,.8)*glow*.8;
    return vec4(radiance*shape.y*pulse*leader,0);
}
