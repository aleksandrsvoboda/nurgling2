"""Bake an original low-resolution advected combustion volume; no third-party art.
Requires numpy, scipy and Pillow. Output is shared RG8 density/heat volume frames.
Inspired by Eulerian fluid advection/projection, not an NVIDIA SDK integration.
"""
from pathlib import Path
import gzip, struct
import numpy as np
from scipy.ndimage import map_coordinates, gaussian_filter, minimum_filter, maximum_filter
from PIL import Image

W,H,D,N=64,64,96,64
OVERLAP=16
z,y,x=np.mgrid[:D,:H,:W].astype(np.float32)
coords=np.array([z,y,x]); rng=np.random.default_rng(1729)
u=np.zeros((3,D,H,W),np.float32); fuel=np.zeros((D,H,W),np.float32)
heat=fuel.copy(); frames=[]
edge=np.minimum.reduce([x,W-1-x,y,H-1-y,z,D-1-z])
fade=np.clip(edge/3,0,1)
noise=np.array([gaussian_filter(rng.normal(size=fuel.shape),1.3) for _ in range(3)],np.float32)
noise*=.18/max(float(noise.std()),1e-5)
controls=rng.uniform(-1,1,(16,80))

def wander(t,stream):
 i=int(np.floor(t)); f=t-i; f=f*f*(3-2*f)
 return controls[stream,i%80]*(1-f)+controls[stream,(i+1)%80]*f

def advect(field, back):
 return map_coordinates(field,back,order=1,mode='constant',cval=0,prefilter=False)

def step(t):
 global u,fuel,heat
 back=coords-u
 u=np.array([advect(v,back) for v in u])*.985
 # Limited MacCormack correction preserves rolling tongues instead of diffusing them.
 def transport(field):
  first=advect(field,back)
  corrected=first+.5*(field-advect(first,coords+u))
  return np.clip(corrected,minimum_filter(field,size=3),maximum_filter(field,size=3))
 fuel=transport(fuel); heat=transport(heat)
 # Buoyancy, a fluctuating multi-lobed burner and weak turbulent forcing.
 u[0]+=heat*.22
 # Vorticity confinement restores vortices lost at this deliberately modest resolution.
 curl=np.array([np.gradient(u[2],axis=1)-np.gradient(u[1],axis=2),
                np.gradient(u[0],axis=2)-np.gradient(u[2],axis=0),
                np.gradient(u[1],axis=0)-np.gradient(u[0],axis=1)])
 magnitude=np.sqrt((curl*curl).sum(0))
 eta=np.array(np.gradient(magnitude)); eta/=np.maximum(np.sqrt((eta*eta).sum(0)),1e-5)
 u+=np.cross(eta,curl,axisa=0,axisb=0,axisc=0)*.3
 u+=noise*(.8+.35*wander(t/17,0)) * np.minimum(heat*2,1)[None]
 div=sum(np.gradient(u[i],axis=i) for i in range(3))
 p=np.zeros_like(fuel)
 for _ in range(12):
  p=(sum(np.roll(p,1,i)+np.roll(p,-1,i) for i in range(3))-div)/6
  p[[0,-1],:,:]=0; p[:,[0,-1],:]=0; p[:,:,[0,-1]]=0
 for i in range(3): u[i]-=np.gradient(p,axis=i)
 u*=fade[None]; u=np.clip(u,-1.8,2.7)
 source=np.zeros_like(fuel)
 for i in range(6):
  angle=i*2.399+0.22*wander(t/23,i+1)
  cx=(W-1)*.5+np.cos(angle)*11; cy=(H-1)*.5+np.sin(angle)*11
  radius=3.2+.65*wander(t/13,i+7)
  source=np.maximum(source,np.exp(-((x-cx)**2+(y-cy)**2)/(2*radius**2)-(z-4)**2/5))
 source*=.94+.06*wander(t/19,14)
 fuel=np.maximum(fuel,source)
 # Reaction-coordinate cooling: flame tips exhaust their fuel, not a fixed cone.
 burn=np.minimum(fuel,.012)
 fuel=np.maximum(fuel-burn,0)*fade
 heat=np.maximum(heat*.975,burn*68)
 heat=np.maximum(heat,source*.95)*fade
 u[0]+=source*.16

for t in range(160+(N+OVERLAP)*3):
 step(t)
 if t>=160 and (t-160)%3==0:
  density=np.clip(fuel*1.6,0,1) * np.clip((D-3-z)/(D*.27),0,1)**1.5
  frames.append(np.stack([density, np.clip(heat,0,1)],-1))
 if t%50==0: print('simulation',t,flush=True)
# Blend TWO MOVING sequences. Fading the tail towards a single frozen frame
# used to slow the flame to a stop at every loop boundary.
tail=[]
for i in range(OVERLAP):
 phase=i/(OVERLAP-1)
 weight=phase*phase*(3-2*phase)
 tail.append(frames[N+i]*(1-weight)+frames[i]*weight)
frames=frames[OVERLAP:N]+tail
rgba=np.rint(np.clip(frames,0,1)*255).astype(np.uint8)
root=Path(__file__).resolve().parents[2]
path=root/'src/nurgling/render/assets/fire-volume.bin.gz'
with gzip.GzipFile(filename=str(path),mode='wb',mtime=0) as f:
 f.write(struct.pack('>5i',0x4e464952,W,H,D,N)); f.write(rgba.tobytes())
# Orthographic diagnostic render of the actual density/heat cache, composited front to back.
previews=[]
for field in frames:
 acc=np.zeros((D,W,3)); trans=np.ones((D,W))
 for j in range(H):
  den=field[:,j,:,0]; h=field[:,j,:,1]
  a=1-np.exp(-den*.22)
  low=np.array([.85,.06,.003]); hot=np.array([1,.72,.13])
  c=low[None,None]+np.clip((h-.15)/.7,0,1)[...,None]*(hot-low)[None,None]
  acc+=trans[...,None]*a[...,None]*c; trans*=1-a
 pixels=np.rint(np.clip(acc[::-1],0,1)*255).astype(np.uint8)
 previews.append(Image.fromarray(pixels).resize((256,384),Image.Resampling.BICUBIC))
previews[0].save(root/'build/fire-volume-preview.gif',save_all=True,append_images=previews[1:],duration=50,loop=0)
sheet=Image.new('RGB',(256*4,384))
for i,f in enumerate([0,12,24,36]): sheet.paste(previews[f],(256*i,0))
sheet.save(root/'build/fire-volume-preview.png')
print(path, path.stat().st_size, 'bytes', flush=True)
