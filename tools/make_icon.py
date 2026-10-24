from PIL import Image, ImageDraw, ImageFilter
import math
S = 512
img = Image.new('RGBA', (S,S), (0,0,0,0))
# vertical gradient
grad = Image.new('RGB', (S,S))
p = grad.load()
for y in range(S):
    for x in range(S):
        t = (x+y)/(2*S)
        r = int(8 + 14*t); g = int(15 + 30*t); b = int(25 + 40*t)
        p[x,y] = (r,g,b)
mask = Image.new('L',(S,S),0)
ImageDraw.Draw(mask).rounded_rectangle([8,8,S-8,S-8], radius=120, fill=255)
img.paste(grad,(0,0),mask)
# glow
glow = Image.new('RGBA',(S,S),(0,0,0,0))
gd = ImageDraw.Draw(glow)
gd.ellipse([70,40,330,300], fill=(70,240,205,90))
gd.ellipse([280,220,470,430], fill=(110,150,255,70))
glow = glow.filter(ImageFilter.GaussianBlur(55))
img.alpha_composite(glow)
# cube
d = ImageDraw.Draw(img, 'RGBA')
cx, cy = 256, 262
s = 150
top = [(cx, cy-s), (cx+s*0.86, cy-s*0.5), (cx, cy), (cx-s*0.86, cy-s*0.5)]
left = [(cx-s*0.86, cy-s*0.5), (cx, cy), (cx, cy+s), (cx-s*0.86, cy+s*0.5)]
right = [(cx+s*0.86, cy-s*0.5), (cx, cy), (cx, cy+s), (cx+s*0.86, cy+s*0.5)]
# shadow
shadow = Image.new('RGBA',(S,S),(0,0,0,0))
ImageDraw.Draw(shadow).ellipse([cx-170, cy+s-20, cx+170, cy+s+80], fill=(0,0,0,120))
img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(24)))
# faces with gradients approximated
d.polygon(left, fill=(38,110,86,235), outline=(190,255,235,110))
d.polygon(right, fill=(24,75,80,235), outline=(190,255,235,80))
d.polygon(top, fill=(116,242,210,245), outline=(230,255,248,210))
# highlights
d.line([top[0],top[1],top[2],top[3],top[0]], fill=(255,255,255,170), width=7, joint='curve')
d.line([left[0],left[1],left[3],left[2]], fill=(210,255,245,90), width=5, joint='curve')
d.line([right[0],right[1],right[3],right[2]], fill=(210,255,245,70), width=5, joint='curve')
# inner lines
d.line([cx-s*0.86, cy-s*0.5, cx, cy], fill=(215,255,245,100), width=4)
d.line([cx+s*0.86, cy-s*0.5, cx, cy], fill=(215,255,245,100), width=4)
# glass sheen
sheen = Image.new('RGBA',(S,S),(0,0,0,0))
sd = ImageDraw.Draw(sheen)
sd.polygon([(140,120),(230,90),(200,250),(110,300)], fill=(255,255,255,45))
sheen = sheen.filter(ImageFilter.GaussianBlur(22))
img.alpha_composite(sheen)
img.save('assets/icon.png')
img.resize((256,256), Image.LANCZOS).save('assets/icon-256.png')
img.save('assets/icon.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
print('icon done')
