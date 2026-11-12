// Логотип студии RC: вертикальный градиент белый→серый + диагональный «перелив» (shine).
shader_type canvas_item;

uniform float alpha : hint_range(0.0, 1.0) = 0.0;
// положение полосы перелива по диагонали: <0 — ещё не началась, >1 — прошла
uniform float shine = -1.0;
uniform float shine_width = 0.09;
// вертикальные границы букв в UV (градиент считаем по буквам, а не по всей текстуре)
uniform float top_v = 0.032;
uniform float bottom_v = 0.97;

void fragment() {
	float m = texture(TEXTURE, UV).a;
	float gy = clamp((UV.y - top_v) / (bottom_v - top_v), 0.0, 1.0);
	vec3 grad = mix(vec3(1.0), vec3(0.47), gy);
	// диагональ слева-снизу направо-вверх
	float d = (UV.x * 1.6 + (1.0 - UV.y)) / 2.6;
	float band = 1.0 - smoothstep(0.0, shine_width, abs(d - shine));
	vec3 col = mix(grad, vec3(1.0), band * 0.9) + vec3(band * 0.15);
	COLOR = vec4(min(col, vec3(1.0)), m * alpha);
}
