import sherpa_onnx, numpy as np, soundfile as sf, os, glob
def make(voice, sents, prefix):
    d=f"/tmp/wt/tts/{voice}"
    onnx=glob.glob(d+"/*.onnx")[0]
    cfg=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=onnx,tokens=d+"/tokens.txt",data_dir=d+"/espeak-ng-data"),num_threads=1))
    tts=sherpa_onnx.OfflineTts(cfg)
    out=[]
    for i,s in enumerate(sents):
        a=tts.generate(s,sid=0,speed=1.0)
        x=np.array(a.samples,dtype=np.float32)
        # rééchantillonnage linéaire vers 16 kHz mono
        n=int(len(x)*16000/a.sample_rate); xr=np.interp(np.linspace(0,len(x)-1,n),np.arange(len(x)),x).astype(np.float32)
        sf.write(f"/tmp/wt/{prefix}{i}.wav",xr,16000,subtype="PCM_16"); out.append((f"{prefix}{i}",s,len(xr)/16000))
    return out
res=[]
res+=make("vits-piper-fr_FR-siwis-medium",["Bonjour à tous, aujourd'hui nous allons parler de l'histoire de l'Empire romain.","Les légions romaines ont conquis une grande partie de l'Europe en quelques siècles.","Merci."],"fr")
res+=make("vits-piper-es_ES-davefx-medium",["Hola a todos, hoy vamos a hablar de la historia de España.","Los romanos conquistaron la península ibérica hace más de dos mil años.","Gracias."],"es")
res+=make("vits-piper-en_US-amy-low",["Hello everyone, today we are going to talk about the history of the Roman Empire.","The legions conquered a large part of Europe in just a few centuries.","Thank you."],"en")
for r in res: print(r)
