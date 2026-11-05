/* hook_reloc.h - Thumb-2 prologue relocation for runtime inline hooks (pure code, also built on the PC for tests).
 *
 * nh_relocate(code, pc, out, out_pc, need) copies whole instructions from `code` (running at thumb address `pc`)
 * until at least `need` bytes are covered and writes to `out` (which will run at `out_pc`, 4-aligned) an equivalent
 * sequence followed by a jump back to pc + consumed. PC-relative instructions are rewritten:
 *   ldr Rt,[pc,#i] / ldr.w / ldrb.w / ldrh.w / ldrsb / ldrsh literal -> ldr.w Rt,=addr ; ldr* Rt,[Rt]
 *   vldr Sd/Dd,[pc,#i]          -> ldr.w ip,=addr ; vldr Sd/Dd,[ip]
 *   adr / adr.w / add.w Rd,pc,#i -> ldr.w Rd,=value
 *   add Rdn, pc                 -> ldr.w ip,=pc+4 ; add Rdn, ip
 *   mov Rd, pc                  -> ldr.w Rd,=pc+4
 *   bl / blx #imm               -> ldr.w ip,=target ; blx ip
 *   b / b.w #imm (unconditional)-> ldr.w pc,=target|1 (and the copy ends there)
 * Refused (returns < 0): conditional branches, cbz/cbnz, it blocks, tbb/tbh, ldrd literal, anything writing pc.
 * Literals go into a pool after the code. Returns bytes written to out (code + pool), *consumed = source bytes taken.
 */
#ifndef NH_RELOC_H
#define NH_RELOC_H

typedef struct { unsigned char *o; unsigned out_pc; int n; unsigned lit[48]; int nlit; int fix_at[48]; int fix_lit[48]; int nfix; } nh_buf;

static void nh_h(nh_buf *b, unsigned hw) { b->o[b->n++] = hw & 255; b->o[b->n++] = (hw >> 8) & 255; }
static int nh_litidx(nh_buf *b, unsigned v) {
    for (int i = 0; i < b->nlit; i++) if (b->lit[i] == v) return i;
    if (b->nlit >= 48) return -1;
    b->lit[b->nlit] = v; return b->nlit++;
}
/* ldr.w Rt, [pc, #lit] (offset fixed later) */
static int nh_ldrlit(nh_buf *b, int rt, unsigned v) {
    int k = nh_litidx(b, v);
    if (k < 0 || b->nfix >= 48) return -1;
    b->fix_at[b->nfix] = b->n; b->fix_lit[b->nfix++] = k;
    nh_h(b, 0xF8DF); nh_h(b, (unsigned)rt << 12);
    return 0;
}

static unsigned nh_rd16(const unsigned char *c, int i) { return c[i] | (c[i + 1] << 8); }

static int nh_relocate(const unsigned char *code, unsigned pc, unsigned char *out, unsigned out_pc, int need, int *consumed) {
    nh_buf B, *b = &B;
    b->o = out; b->out_pc = out_pc; b->n = 0; b->nlit = 0; b->nfix = 0;
    int i = 0, ended = 0;
    while (i < need) {
        unsigned ipc = pc + i;            /* address of this instruction */
        unsigned P = ipc + 4;             /* value of PC when it executes */
        unsigned A = P & ~3u;             /* Align(PC, 4) for literals */
        unsigned h1 = nh_rd16(code, i);
        int wide = (h1 & 0xF800) == 0xE800 || (h1 & 0xF800) == 0xF000 || (h1 & 0xF800) == 0xF800;
        if (!wide) {
            if ((h1 & 0xF800) == 0x4800) {                       /* ldr Rt, [pc, #imm8*4] */
                int rt = (h1 >> 8) & 7;
                if (nh_ldrlit(b, rt, A + (h1 & 0xff) * 4)) return -1;
                nh_h(b, 0xF8D0 | rt); nh_h(b, (unsigned)rt << 12);  /* ldr.w Rt, [Rt] */
            } else if ((h1 & 0xF800) == 0xA000) {                /* adr Rd, #imm8*4 */
                if (nh_ldrlit(b, (h1 >> 8) & 7, A + (h1 & 0xff) * 4)) return -1;
            } else if ((h1 & 0xFF78) == 0x4478) {                /* add Rdn, pc */
                int rdn = (h1 & 7) | ((h1 >> 4) & 8);
                if (rdn == 15) return -2;
                if (nh_ldrlit(b, 12, P)) return -1;
                nh_h(b, 0x4400 | (12 << 3) | (rdn & 7) | ((rdn & 8) << 4));
            } else if ((h1 & 0xFF78) == 0x4678) {                /* mov Rd, pc */
                int rd = (h1 & 7) | ((h1 >> 4) & 8);
                if (rd == 15) return -2;
                if (nh_ldrlit(b, rd, P)) return -1;
            } else if ((h1 & 0xF800) == 0xE000) {                /* b #imm11 */
                int off = (int)((h1 & 0x7ff) << 21) >> 20;
                if (nh_ldrlit(b, 15, (P + off) | 1)) return -1;
                ended = 1; i += 2; break;
            } else if ((h1 & 0xF000) == 0xD000) return -3;       /* b<cond>, svc, udf */
            else if ((h1 & 0xF500) == 0xB100) return -4;         /* cbz / cbnz */
            else if ((h1 & 0xFF00) == 0xBF00 && (h1 & 0xF)) return -5;   /* it */
            else if ((h1 & 0xFF00) == 0x4700 && ((h1 >> 3) & 15) == 15) return -6;   /* bx/blx pc */
            else if ((h1 & 0xFE00) == 0xBC00 && (h1 & 0x100)) return -7; /* pop {..., pc} */
            else if ((h1 & 0xFF00) == 0x4600 && ((h1 & 7) | ((h1 >> 4) & 8)) == 15) return -8;  /* mov pc, Rm */
            else nh_h(b, h1);
            i += 2;
            continue;
        }
        unsigned h2 = nh_rd16(code, i + 2);
        if ((h1 & 0xF800) == 0xF000 && (h2 & 0x8000)) {         /* branches */
            unsigned S = (h1 >> 10) & 1, J1 = (h2 >> 13) & 1, J2 = (h2 >> 11) & 1;
            unsigned I1 = !(J1 ^ S), I2 = !(J2 ^ S);
            int kind = (h2 >> 12) & 5;                            /* bits 14,12 */
            if (kind == 5 || kind == 4) {                        /* bl (5) / blx (4) */
                int off = (int)((S << 24) | (I1 << 23) | (I2 << 22) | ((h1 & 0x3ff) << 12) | ((h2 & 0x7ff) << 1)) << 7 >> 7;
                unsigned t = P + off;
                if (kind == 4) t = (A + off) & ~3u; else t |= 1;
                if (nh_ldrlit(b, 12, t)) return -1;
                nh_h(b, 0x47E0);                                  /* blx ip */
            } else if (kind == 1) {                              /* b.w */
                int off = (int)((S << 24) | (I1 << 23) | (I2 << 22) | ((h1 & 0x3ff) << 12) | ((h2 & 0x7ff) << 1)) << 7 >> 7;
                if (nh_ldrlit(b, 15, (P + off) | 1)) return -1;
                ended = 1; i += 4; break;
            } else if ((h2 & 0xD000) == 0x8000 && ((h1 >> 6) & 0xE) != 0xE) return -9;   /* b<cond>.w */
            else { nh_h(b, h1); nh_h(b, h2); }                    /* msr/mrs/hints */
            i += 4;
            continue;
        }
        if ((h1 & 0xFF7F) == 0xF85F || (h1 & 0xFF7F) == 0xF81F || (h1 & 0xFF7F) == 0xF83F
                || (h1 & 0xFF7F) == 0xF91F || (h1 & 0xFF7F) == 0xF93F) {   /* ldr/ldrb/ldrh/ldrsb/ldrsh literal */
            int rt = h2 >> 12;
            if (rt == 15) return -10;
            unsigned addr = (h1 & 0x80) ? A + (h2 & 0xfff) : A - (h2 & 0xfff);
            if (nh_ldrlit(b, rt, addr)) return -1;
            nh_h(b, (h1 & 0xFF70) | 0x0080 | rt); nh_h(b, (unsigned)rt << 12);          /* ldr*.w Rt, [Rt, #0] */
        } else if ((h1 & 0xFF3F) == 0xED1F && ((h2 & 0x0E00) == 0x0A00)) {   /* vldr S/D, [pc, #imm8*4] */
            unsigned addr = (h1 & 0x80) ? A + (h2 & 0xff) * 4 : A - (h2 & 0xff) * 4;
            if (nh_ldrlit(b, 12, addr)) return -1;
            nh_h(b, (h1 & 0xFFF0) | 0x0080 | 12); nh_h(b, h2 & 0xFF00);                 /* vldr, [ip, #0] */
        } else if ((h1 & 0xFE5F) == 0xE85F) return -11;           /* ldrd literal */
        else if ((h1 & 0xFBFF) == 0xF20F || (h1 & 0xFBFF) == 0xF2AF) {   /* adr.w (add.w/sub.w Rd, pc, #imm12) */
            unsigned imm = ((h1 >> 10) & 1) << 11 | ((h2 >> 12) & 7) << 8 | (h2 & 0xff);
            int rd = (h2 >> 8) & 15;
            if (rd == 15) return -12;
            if (nh_ldrlit(b, rd, (h1 & 0xFBFF) == 0xF20F ? A + imm : A - imm)) return -1;
        } else if ((h1 & 0xFFF0) == 0xE8D0 && (h2 & 0xFFE0) == 0xF000) return -13;   /* tbb/tbh */
        else if ((h1 & 0xFFD0) == 0xE890 && (h2 & 0x8000)) return -14;               /* ldm with pc */
        else if ((h1 & 0xFFFF) == 0xE8BD && (h2 & 0x8000)) return -14;               /* pop.w {.., pc} */
        else if ((h1 & 0xFFFF) == 0xF85D && h2 == 0xFB04) return -14;                /* pop.w {pc} / ldr pc,[sp],#4 */
        else if ((h1 & 0xFE00) == 0xEA00 && (h1 & 15) == 15 && ((h1 >> 5) & 15) != 2 && ((h1 >> 5) & 15) != 3) return -15;   /* reg op, Rn = pc */
        else if ((h1 & 0xFE00) == 0xEB00 && (h1 & 15) == 15) return -15;
        else if ((h1 & 0xFA00) == 0xF000 && !(h2 & 0x8000) && (h1 & 15) == 15 && ((h1 >> 5) & 15) != 2 && ((h1 >> 5) & 15) != 3) return -15;   /* imm op, Rn = pc */
        else if (((h2 >> 12) & 15) == 15 && (h1 & 0xFE00) == 0xF800 && (h1 & 0x0010)) return -16;   /* load into pc */
        else { nh_h(b, h1); nh_h(b, h2); }
        i += 4;
    }
    if (!ended) { if (nh_ldrlit(b, 15, (pc + i) | 1)) return -1; }
    /* literal pool, 4-aligned */
    if (b->n & 2) nh_h(b, 0xBF00);
    int pool = b->n;
    for (int k = 0; k < b->nlit; k++) {
        unsigned v = b->lit[k];
        out[b->n++] = v; out[b->n++] = v >> 8; out[b->n++] = v >> 16; out[b->n++] = v >> 24;
    }
    for (int k = 0; k < b->nfix; k++) {
        int at = b->fix_at[k];
        unsigned a = (out_pc + at + 4) & ~3u, la = out_pc + pool + b->fix_lit[k] * 4;
        unsigned off = la - a;
        unsigned hw2 = out[at + 2] | (out[at + 3] << 8);
        hw2 = (hw2 & 0xF000) | (off & 0xfff);
        out[at + 2] = hw2; out[at + 3] = hw2 >> 8;
    }
    *consumed = i;
    return b->n;
}

#endif
