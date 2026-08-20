(() => {
  'use strict';

  const TOKEN_RX=/^pk\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/;
  const STYLE_RX=/^mapbox:\/\/styles\/[A-Za-z0-9_-]{1,64}\/[A-Za-z0-9_-]{1,128}$/;

  function config(){
    const source=window.MR_BOOTSTRAP?.mapbox||window.MRA?.mapbox||{};
    const token=String(source.token||'').trim();
    const style=String(source.style||'').trim();
    return {
      enabled:source.enabled===true&&TOKEN_RX.test(token)&&STYLE_RX.test(style),
      token,
      style
    };
  }

  function mount(options={}){
    const cfg=config();
    const host=options.host;
    const leaflet=options.leafletMap;
    if(!cfg.enabled||!host||!host.parentNode||!leaflet||!navigator.onLine||!window.mapboxgl?.Map){
      options.onFallback?.('unavailable');
      return null;
    }
    if(typeof window.mapboxgl.supported==='function'&&!window.mapboxgl.supported()){
      options.onFallback?.('unsupported');
      return null;
    }

    const base=document.createElement('div');
    base.className='mapbox-base';
    base.setAttribute('aria-hidden','true');
    const controls=document.createElement('div');
    controls.className='mr-mapbox-controls';
    controls.setAttribute('aria-label','Créditos do mapa Mapbox');
    host.parentNode.insertBefore(base,host);
    host.parentNode.appendChild(controls);

    let map=null,loaded=false,failed=false,visible=true,timer=0;
    const callFallback=reason=>{if(failed)return;failed=true;loaded=false;base.remove();controls.remove();options.onFallback?.(reason);};
    const sync=()=>{
      if(!map||failed)return;
      const center=leaflet.getCenter();
      map.jumpTo({center:[center.lng,center.lat],zoom:leaflet.getZoom(),bearing:0,pitch:0});
    };
    const moveControls=()=>{
      if(!map||failed)return;
      for(const selector of ['.mapboxgl-ctrl-bottom-left','.mapboxgl-ctrl-bottom-right']){
        const node=base.querySelector(selector);if(node)controls.appendChild(node);
      }
    };
    const setVisible=value=>{
      visible=!!value&&loaded&&!failed;
      base.classList.toggle('is-visible',visible);
      controls.classList.toggle('is-visible',visible);
      if(visible){sync();map?.resize();}
      return visible;
    };

    try{
      window.mapboxgl.accessToken=cfg.token;
      const center=leaflet.getCenter();
      map=new window.mapboxgl.Map({
        container:base,
        style:cfg.style,
        center:[center.lng,center.lat],
        zoom:leaflet.getZoom(),
        interactive:false,
        attributionControl:true,
        logoPosition:'bottom-left',
        cooperativeGestures:false,
        fadeDuration:0
      });
      timer=window.setTimeout(()=>callFallback('timeout'),15000);
      map.once('load',()=>{
        if(failed)return;
        clearTimeout(timer);loaded=true;moveControls();setVisible(visible);sync();options.onReady?.();
      });
      map.on('error',event=>{
        if(!loaded)callFallback(event?.error?.message||'load-error');
      });
      leaflet.on('move zoom resize',sync);
    }catch(error){callFallback(error?.message||'init-error');return null;}

    return {
      ready:()=>loaded&&!failed,
      setVisible,
      resize:()=>{if(loaded&&!failed){map.resize();sync();}},
      destroy:()=>{clearTimeout(timer);leaflet.off('move zoom resize',sync);try{map?.remove();}catch(_){}base.remove();controls.remove();}
    };
  }

  window.MRMapboxBase=Object.freeze({mount});
})();
