// Небо Story Craft: градиент горизонт→зенит, квадратное солнце, звёзды ночью, блочные облака.
shader_type sky;

uniform vec3 zenith : source_color = vec3(0.33, 0.55, 0.95);
uniform vec3 horizon : source_color = vec3(0.68, 0.82, 1.0);
uniform vec3 sun_disc : source_color = vec3(1.0, 0.95, 0.75);
uniform vec3 clouds : source_color = vec3(1.0, 1.0, 1.0);
uniform vec3 sun_dir = vec3(0.45, 0.8, 0.35);
uniform float sun_size = 0.07;
uniform float stars = 0.0;
uniform float cloud_amount = 0.45;
uniform vec2 cloud_offset = vec2(0.0, 0.0);

float hash2(vec2 p) {
	return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void sky() {
	vec3 dir = normalize(EYEDIR);
	float y = dir.y;
	vec3 col = mix(horizon, zenith, clamp(pow(max(y, 0.0), 0.55), 0.0, 1.0));
	if (y < 0.0) {
		col = horizon * mix(1.0, 0.8, clamp(-y * 3.0, 0.0, 1.0));
	}
	// звёзды
	if (stars > 0.0 && y > 0.0) {
		vec3 q = floor(dir * 160.0);
		float h = fract(sin(dot(q, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
		if (h > 0.996) {
			col += vec3(stars * (h - 0.996) / 0.004);
		}
	}
	// квадратное солнце (как в Minecraft)
	vec3 s = normalize(sun_dir);
	float d = dot(dir, s);
	if (d > 0.0) {
		vec3 up = abs(s.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
		vec3 t = normalize(cross(up, s));
		vec3 b = cross(s, t);
		vec2 p = vec2(dot(dir, t), dot(dir, b)) / d;
		if (max(abs(p.x), abs(p.y)) < sun_size) {
			col = sun_disc;
		}
	}
	// блочные облака на «потолке»
	if (y > 0.03 && cloud_amount > 0.0) {
		// без TIME: иначе radiance неба пересчитывалась бы каждый кадр (облака и так почти неподвижны)
		vec2 uv = dir.xz / y * 0.5 + cloud_offset;
		vec2 c = floor(uv * 5.0);
		float n = hash2(c);
		float n2 = hash2(floor(uv * 2.5) + 17.0);
		if (n * 0.5 + n2 * 0.5 < cloud_amount * 0.75) {
			col = mix(col, clouds, 0.8 * smoothstep(0.03, 0.25, y));
		}
	}
	COLOR = col;
}
