float lightning_pulse(float t, vec4 timing) {
    float strike=exp(-pow((t-.07)/.027,2.0));
    float second=exp(-pow((t-timing.y)/.034,2.0));
    float third=exp(-pow((t-timing.z)/.044,2.0));
    float pulse=1.2*strike+.85*second+.40*third;
    pulse+=.12*exp(-t*6.0);
    return pulse*(1.0-smoothstep(.58,.80,t));
}
