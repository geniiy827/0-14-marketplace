// Размытие и затемнение живого фона под панелями меню.
// 3×3 = 9 выборок со смещением 1.5 шага: билинейная фильтрация между соседними пикселями даёт ту же мягкость,
// что и прежние 5×5 = 25 выборок, но почти втрое дешевле.
shader_type canvas_item;

uniform sampler2D screen_tex : hint_screen_texture, filter_linear;
uniform float dim : hint_range(0.0, 1.0) = 0.35;
uniform float radius = 2.5;

void fragment() {
	vec2 px = SCREEN_PIXEL_SIZE * radius * 1.5;
	vec3 c = vec3(0.0);
	for (int x = -1; x <= 1; x++) {
		for (int y = -1; y <= 1; y++) {
			c += texture(screen_tex, SCREEN_UV + vec2(float(x), float(y)) * px).rgb;
		}
	}
	COLOR = vec4(c / 9.0 * (1.0 - dim), 1.0);
}
