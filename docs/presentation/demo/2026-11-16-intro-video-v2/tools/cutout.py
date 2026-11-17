import sys, numpy as np
from PIL import Image
from scipy import ndimage
def cut(src, dst, box=None, tol=26):
    im=Image.open(src).convert('RGB')
    if box: im=im.crop(box)
    a=np.asarray(im).astype(int)
    h,w,_=a.shape
    border=np.concatenate([a[0],a[-1],a[:,0],a[:,-1]])
    bg=np.median(border,axis=0)
    close=(np.abs(a-bg).max(axis=2)<=tol)
    lab,_=ndimage.label(close)
    edge=set(np.unique(np.concatenate([lab[0],lab[-1],lab[:,0],lab[:,-1]])))-{0}
    mask=np.isin(lab,list(edge))
    alpha=np.where(mask,0,255).astype(np.uint8)
    alpha=ndimage.gaussian_filter(alpha.astype(float),0.7).clip(0,255).astype(np.uint8)
    out=np.dstack([np.asarray(im),alpha])
    # trim to content
    ys,xs=np.where(alpha>8); out=out[max(ys.min()-6,0):ys.max()+7, max(xs.min()-6,0):xs.max()+7]
    Image.fromarray(out,'RGBA').save(dst)
    print(dst, out.shape[1], out.shape[0])
cut('harin-pose-c.png','cut/jump.png')
cut('harin-thumbs.png','cut/thumbs.png')
names=['tense','dejected','joy','panic','relief','surprise']
W,H=1536,1024
for i,n in enumerate(names):
    c,r=i%3,i//3
    cut('harin-sheet-b.png',f'cut/bust-{n}.png',box=(c*512,r*512,(c+1)*512,(r+1)*512))
cut('harin-sheet-a.png','cut/back.png',box=(1130,30,1500,1000))
cut('harin-sheet-a.png','cut/front.png',box=(40,30,420,1000))
