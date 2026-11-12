// Размытие и затемнение живого фона под панелями меню.
shader_type canvas_item;

uniform sampler2D screen_tex : hint_screen_texture, filter_linear;
uniform float dim : hint_range(0.0, 1.0) = 0.35;
uniform float radius = 2.5;
uniform float amount : hint_range(0.0, 1.0) = 1.0;

void fragment() {
	vec2 px = SCREEN_PIXEL_SIZE * radius;
	vec3 c = vec3(0.0);
	for (int x = -2; x <= 2; x++) {
		for (int y = -2; y <= 2; y++) {
			c += texture(screen_tex, SCREEN_UV + vec2(float(x), float(y)) * px).rgb;
		}
	}
	c /= 25.0;
	vec3 orig = texture(screen_tex, SCREEN_UV).rgb;
	vec3 res = mix(orig, c * (1.0 - dim), amount);
	COLOR = vec4(res, 1.0);
}
