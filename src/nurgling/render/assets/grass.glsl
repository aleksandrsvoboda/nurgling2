vec4 grass_position(vec4 root,vec4 shape,float time,mat4 trailA,mat4 trailB) {
    float t=shape.x;
    vec2 forward=vec2(cos(shape.z),sin(shape.z)),right=vec2(-forward.y,forward.x);
    float height=root.w;
    float wind=sin(time*1.35+root.x*.11+root.y*.07+shape.z*.3)*.16;
    wind+=sin(time*2.2+root.x*.31-root.y*.2+shape.z)*.055;
    vec2 offset=(forward*.20+vec2(1,.35)*wind)*height*t*t;
    vec2 push=vec2(0);float weight=0;
    for(int i=0;i<8;i++) {
        vec4 point=i<4?trailA[i]:trailB[i-4];
        float age=max(0.0,time-point.w);
        float fade=1.0-smoothstep(.10,1.4,age);
        vec2 delta=root.xy-point.xy;float distance=length(delta);
        float influence=(1.0-smoothstep(.0,3.5,distance))*fade;
        influence*=1.0-smoothstep(2.0,5.0,abs(root.z-point.z));
        // Strongest contact wins: overlapping samples cannot flatten blades eight times.
        if(influence>weight){weight=influence;push=(distance>.01?delta/distance:forward)*influence;}
    }
    offset+=push*height*.85*t*t;
    float z=height*t*(1.0-weight*.48*t);
    vec2 width=right*shape.y*shape.w*(1.0-t);
    return vec4(root.xyz+vec3(offset+width,z),1);
}
