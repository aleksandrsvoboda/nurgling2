vec4 lightning_flash(vec4 color, vec2 uv, float age, sampler2D depths, vec4 timing) {
    if(age<0.0 || age>=.90) return color;
    // Affect the world view only; leave empty space outside loaded terrain intact.
    if(texture(depths,uv).r>=.99999) return color;
    float pulse=clamp(lightning_pulse(age/timing.x,timing)*.85,0.0,1.0);
    if(pulse<=.0001) return color;
    // A cool, bright flash with a soft highlight shoulder: keep terrain detail
    // visible instead of replacing the frame with an opaque white rectangle.
    vec3 original=clamp(color.rgb,0.0,1.0);
    vec3 lit=1.0-pow(1.0-original,vec3(1.0+4.0*pulse));
    lit+=(1.0-lit)*vec3(.55,.68,1.0)*pulse*.22;
    return vec4(lit,color.a);
}
