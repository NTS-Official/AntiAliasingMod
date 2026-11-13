int effectConflictReasons(vec4 conflict) {
    if (conflict.r <= 0.00001) return 0;
    return (conflict.g / conflict.r > 0.25 ? 1 : 0) |
        (conflict.b / conflict.r > 0.02 ? 2 : 0) |
        (conflict.a / conflict.r > 0.002 ? 4 : 0);
}
