#!/usr/bin/env python3
"""Launch a separate offline test instance using an existing licensed Prism installation.

Only the specified world is copied. Accounts, other mods and the original world are untouched.
The output directory must not exist. Test controls/settings live in the separate test harness.
"""
from pathlib import Path
import argparse, json, os, shutil, subprocess, zipfile

p = argparse.ArgumentParser()
p.add_argument('--prism', type=Path, required=True)
p.add_argument('--world', type=Path, required=True)
p.add_argument('--fabric-api', type=Path, help='Optional; include when an additional mod requires Fabric API')
p.add_argument('--output', type=Path, required=True)
p.add_argument('--options', type=Path, help='Use the same graphics/options template for comparisons')
p.add_argument('--vanilla', action='store_true', help='Fabric + same harness without Trinium')
p.add_argument('--java-fallback', action='store_true', help='Disable native loading to exercise Java fallback')
p.add_argument('--mode-off', action='store_true', help='Verify disabled startup and a saved shader without loading Trinium native code')
p.add_argument('--jar', type=Path, help='Compare a specific built Trinium JAR')
p.add_argument('--graphics-off', action='store_true')
p.add_argument('--scene', choices=['coast','dense','cave','village','entities'], default='coast')
p.add_argument('--borderless',action='store_true',help='Test-only borderless window for exact full-HD client area')
p.add_argument('--width',type=int,default=960)
p.add_argument('--height',type=int,default=540)
p.add_argument('--render-distance',type=int,default=8)
p.add_argument('--simulation-distance',type=int,default=5)
p.add_argument('--warmup',type=int,default=20)
p.add_argument('--duration',type=int,default=30)
p.add_argument('--max-memory',type=int,default=1800)
p.add_argument('--camera',help='Local test camera x,y,z,yaw,pitch')
p.add_argument('--ui',action='store_true')
p.add_argument('--ui-world',action='store_true')
p.add_argument('--standalone-restart-from',type=Path,help='Copy only independent shader configuration to test restart restoration')
p.add_argument('--standalone-shaders',action='store_true',help='Exercise independent shaders without Iris or Sodium')
p.add_argument('--java',default='java',help='Java executable for the game and harness compiler')
p.add_argument('--test-models', action='store_true')
p.add_argument('--native-packet-gpu',action='store_true')
p.add_argument('--climate-probe',action='store_true')
p.add_argument('--enable-climate-distance',action='store_true')
p.add_argument('--disable-climate-distance',action='store_true')
p.add_argument('--f3', action='store_true', help='Capture F3 during warm-up, then hide it before sampling')
p.add_argument('--indirect-parity',action='store_true',help='Local test only: compare naive/indirect pixels before measuring the experimental renderer')
p.add_argument('--direct-debug',action='store_true',help='Read back prototype buffers for debugging, outside FPS measurements')
p.add_argument('--direct-individual',action='store_true',help='Debug ablation: individual draws with exact array data and base-instance IDs')
p.add_argument('--terrain-submit',action='store_true',help='Use scoped submission of unchanged vanilla terrain draws')
p.add_argument('--direct-terrain',action='store_true',help='Enable opt-in direct multi-draw terrain prototype')
p.add_argument('--direct-parity',action='store_true',help='Compare naive/direct/naive color and depth in the same extracted frame')
p.add_argument('--extra-mod', type=Path, action='append', default=[])
p.add_argument('--resourcepack', type=Path)
p.add_argument('--shaderpack', type=Path)
p.add_argument('--additional-shaderpack',type=Path,action='append',default=[])
p.add_argument('--cache-from', type=Path)
p.add_argument('--monitor',type=int,default=1,help='Test monitor in desktop coordinate order (default 1)')
p.add_argument('--new-world', action='store_true')
p.add_argument('--loading-grid-test',action='store_true')
p.add_argument('--enable-loading-grid',action='store_true')
p.add_argument('--disable-loading-grid',action='store_true')
p.add_argument('--enable-entity-vertex-batch',action='store_true')
p.add_argument('--disable-entity-vertex-batch',action='store_true')
p.add_argument('--disable-block-entity-culling',action='store_true')
p.add_argument('--culling-test',action='store_true')
p.add_argument('--reload', action='store_true')
p.add_argument('--reload-count',type=int,default=1,help='1..10 sequential reloads, at least ten seconds apart')
p.add_argument('--profile', action='store_true', help='Record JFR; do not compare these FPS with unprofiled runs')
p.add_argument('--profile-settings',type=Path,help='Explicit bounded JFR event configuration')
p.add_argument('--profile-delay',type=int,default=0)
p.add_argument('--profile-duration',type=int,default=0)
args = p.parse_args()
if sum([args.culling_test,args.ui,args.loading_grid_test,args.standalone_shaders]) > 1: p.error('Choose only one specialized UI or rendering probe')
if (args.native_packet_gpu or args.climate_probe) and any([args.culling_test,args.ui,args.loading_grid_test,args.standalone_shaders]): p.error('Native/climate probes require the regular offline world harness')
if args.climate_probe and args.disable_climate_distance: p.error('Climate probe requires the experimental climate mixin')
if args.mode_off and any([args.vanilla,args.java_fallback,args.ui,args.culling_test,args.loading_grid_test,args.standalone_shaders,args.native_packet_gpu,args.climate_probe,args.extra_mod]): p.error('--mode-off requires the regular Trinium-only offline harness')
if not 1<=args.reload_count<=10: p.error('--reload-count must be 1..10')
root = Path(__file__).resolve().parents[1]
release_version = json.loads((root/'src/main/resources/fabric.mod.json').read_text())['version']
built_jar = root/f'target/Trinium-{release_version}-mc26.3.jar'
test_compile_jar = (args.jar or built_jar).resolve()
prism = args.prism.resolve()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)
(out/'mods').mkdir()
if not args.new_world: shutil.copytree(args.world, out/'saves/TriniumTest', ignore=shutil.ignore_patterns('session.lock'))
if args.fabric_api: shutil.copy2(args.fabric_api, out/'mods')
if not args.vanilla:
    shutil.copy2(test_compile_jar, out/'mods')
for mod in args.extra_mod: shutil.copy2(mod,out/'mods')
if args.cache_from:
    for name in ['shader-cache-v1','iris-program-cache-v1']:
        source=args.cache_from/'config/trinium'/name
        if source.exists(): shutil.copytree(source,out/'config/trinium'/name)
if args.resourcepack:
    (out/'resourcepacks').mkdir();shutil.copy2(args.resourcepack,out/'resourcepacks')
if args.shaderpack:
    (out/'shaderpacks').mkdir();shutil.copy2(args.shaderpack,out/'shaderpacks')
    (out/'config').mkdir(exist_ok=True)
    (out/'config/iris.properties').write_text('enableShaders=true\nshaderPack='+args.shaderpack.name+'\n')
if args.standalone_restart_from:
    (out/'config/trinium').mkdir(parents=True,exist_ok=True)
    shutil.copy2(args.standalone_restart_from/'config/trinium/shaders.properties',out/'config/trinium/shaders.properties')
if args.mode_off:
    (out/'config/trinium').mkdir(parents=True,exist_ok=True)
    (out/'config/trinium/trinium.properties').write_text('mode=off\n')
for shader in args.additional_shaderpack:
    (out/'shaderpacks').mkdir(exist_ok=True);shutil.copy2(shader,out/'shaderpacks')
system = {'linux':'linux', 'win32':'windows','darwin':'osx'}[os.sys.platform]
libraries = []
for component in ['net.minecraft/26.3.json','net.fabricmc.fabric-loader/0.20.1.json','org.lwjgl3/3.4.3.json']:
    meta = json.loads((prism/'meta'/component).read_text())
    for library in meta.get('libraries', []):
        rules = library.get('rules')
        if rules and not any(r.get('action') == 'allow' and r.get('os',{}).get('name') == system for r in rules):
            continue
        parts = library['name'].split(':')
        group,artifact,version = parts[:3]
        classifier = '-'+parts[3] if len(parts)>3 else ''
        jar = prism/'libraries'/group.replace('.','/')/artifact/version/f'{artifact}-{version}{classifier}.jar'
        if jar.is_file(): libraries.append(jar)
libraries.append(prism/'libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar')
cp = os.pathsep.join(map(str,dict.fromkeys(libraries)))
classes = out/'harness-classes'
classes.mkdir()
subprocess.run(['javac','-proc:none','-cp',cp+os.pathsep+str(test_compile_jar),'-d',str(classes),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ClientTestMixin.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/NativePacketGpuProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ModeOffProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ClimateDistanceProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ClimateObjectSizeAgent.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/LoadingGridOracle.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/LoadingGridProbeMixin.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/LoadingMarkerEvent.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/UiProbeMixin.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/ui/UiAlphaRegressionProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/StandaloneShaderProbeMixin.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ModelParityProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/EntityModelParityProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/EntityStressScene.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/TerrainCacheProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/DrawCacheProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/IndirectParityProbe.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/DirectParityProbeMixin.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/DirectShaderSourceProbeMixin.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/DirectUniformProbeMixin.java'),str(root/'src/test/java/fr/pierrecreeper/trinium/ShaderCacheTest.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ChestCullingClientMixin.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ChestCullingParityMixin.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ChestCullingExtractorInvoker.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ChestCullingOracle.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/ChestCullingPixels.java'),str(root/'src/integration/java/fr/pierrecreeper/trinium/test/WindowPlacementMixin.java')],check=True)
(classes/'fabric.mod.json').write_text(json.dumps({'schemaVersion':1,'id':'trinium-test-harness','version':'1.0.0','name':'Trinium integration test','environment':'client','mixins':['trinium-test.mixins.json'],'depends':{'minecraft':'26.3','fabricloader':'>=0.19.5'}}))
probe_mixins = ['ChestCullingClientMixin','ChestCullingParityMixin','ChestCullingExtractorInvoker'] if args.culling_test else ['LoadingGridProbeMixin' if args.loading_grid_test else 'StandaloneShaderProbeMixin' if args.standalone_shaders else 'UiProbeMixin' if args.ui else 'ClientTestMixin']
(classes/'trinium-test.mixins.json').write_text(json.dumps({'required':True,'package':'fr.pierrecreeper.trinium.test','compatibilityLevel':'JAVA_25','client':probe_mixins+['WindowPlacementMixin']+(['DirectParityProbeMixin'] if args.direct_parity else [])+(['DirectShaderSourceProbeMixin','DirectUniformProbeMixin'] if args.direct_debug else []),'injectors':{'defaultRequire':1}}))
with zipfile.ZipFile(out/'mods/trinium-test-harness.jar','w',zipfile.ZIP_DEFLATED) as z:
    for file in classes.rglob('*'):
        if file.is_file(): z.write(file,file.relative_to(classes))
if args.climate_probe:
    agent_class='fr/pierrecreeper/trinium/probe/ClimateObjectSizeAgent.class'
    with zipfile.ZipFile(out/'climate-size-agent.jar','w',zipfile.ZIP_DEFLATED) as agent:
        agent.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: fr.pierrecreeper.trinium.probe.ClimateObjectSizeAgent\n\n')
        agent.write(classes/agent_class,agent_class)
if args.options:
    shutil.copy2(args.options,out/'options.txt')
else:
    (out/'options.txt').write_text('enableVsync:false\nmaxFps:260\npauseOnLostFocus:false\nfullscreen:false\noverrideWidth:960\noverrideHeight:540\nguiScale:2\nsoundCategory_master:0.0\n')
options=(out/'options.txt').read_text().splitlines()
forced={'fullscreen':'false','overrideWidth':str(args.width),'overrideHeight':str(args.height),'tutorialStep':'none','enableVsync':'false','maxFps':'260','inactivityFpsLimit':'"minimized"'}
if args.resourcepack: forced['resourcePacks']=json.dumps(['vanilla','file/'+args.resourcepack.name],separators=(',',':'))
options=[line for line in options if line.split(':',1)[0] not in forced]
(out/'options.txt').write_text('\n'.join(options+[k+':'+v for k,v in forced.items()])+'\n')
command = [args.java,'-Xms512m','-Xmx'+str(args.max_memory)+'m','--enable-native-access=ALL-UNNAMED']
if system=='linux': command += ['-Dorg.lwjgl.opengl.contextAPI=native']
command += ['-Dtrinium.testScene='+args.scene,'-Dtrinium.testMonitor='+str(args.monitor)]
if args.mode_off: command += ['-Dtrinium.testModeOff=true']
command += ['-Dtrinium.testWidth='+str(args.width),'-Dtrinium.testHeight='+str(args.height),'-Dtrinium.testRenderDistance='+str(args.render_distance),'-Dtrinium.testSimulationDistance='+str(args.simulation_distance),'-Dtrinium.testWarmup='+str(args.warmup),'-Dtrinium.testDuration='+str(args.duration)]
if args.borderless: command += ['-Dtrinium.testBorderless=true']
if args.camera:
    camera=[float(v) for v in args.camera.split(',')]
    if len(camera)!=5: raise SystemExit('--camera needs five numbers')
    command += ['-Dtrinium.testCamera='+','.join(map(str,camera))]
if args.enable_entity_vertex_batch: command += ['-Dtrinium.experimentalEntityVertexBatch=true']
if args.disable_block_entity_culling: command += ['-Dtrinium.disableBlockEntityCulling=true']
if args.disable_entity_vertex_batch: command += ['-Dtrinium.disableEntityVertexBatch=true']
if args.enable_loading_grid or args.loading_grid_test: command += ['-Dtrinium.experimentalLoadingGrid=true']
if args.disable_loading_grid: command += ['-Dtrinium.disableLoadingGrid=true']
if args.new_world: command += ['-Dtrinium.testNewWorld=true']
if args.reload: command += ['-Dtrinium.testReload=true','-Dtrinium.testReloadCount='+str(args.reload_count)]
if args.standalone_restart_from: command += ['-Dtrinium.testShaderRestart=true']
if args.ui_world: command += ['-Dtrinium.testUiWorld=true']
if args.test_models: command += ['-Dtrinium.testModels=true']
if args.native_packet_gpu: command += ['-Dtrinium.testNativePacketGpu=true']
if args.enable_climate_distance or args.climate_probe: command += ['-Dtrinium.experimentalClimateDistance=true']
if args.climate_probe: command += ['-Dtrinium.testClimateProbe=true','-javaagent:'+str(out/'climate-size-agent.jar')]
if args.disable_climate_distance: command += ['-Dtrinium.disableClimateDistance=true']
if args.f3: command += ['-Dtrinium.testF3=true']
if args.indirect_parity: command += ['-Dtrinium.testIndirectParity=true']
if args.direct_terrain or (args.direct_parity and not args.terrain_submit): command += ['-Dtrinium.experimentalDirectTerrain=true']
if args.terrain_submit: command += ['-Dtrinium.experimentalTerrainSubmit=true']
if args.direct_debug: command += ['-Dtrinium.debugDirectTerrain=true']
if args.direct_individual: command += ['-Dtrinium.debugDirectIndividual=true']
if args.graphics_off: command += ['-Dtrinium.disableGraphics=true']
if args.profile:
    command += ['-Dtrinium.testLoadingMarkers=true']
    settings=str(args.profile_settings.resolve()) if args.profile_settings else 'profile'
    recording='-XX:StartFlightRecording=settings='+settings+',dumponexit=true,filename='+str(out/'profile.jfr')
    if args.profile_delay: recording+=',delay='+str(args.profile_delay)+'s'
    if args.profile_duration: recording+=',duration='+str(args.profile_duration)+'s'
    command += [recording]
if args.java_fallback: command += ['-Dtrinium.disableNative=true']
command += ['-cp',cp,'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','TriniumTest',
    '--version','26.3','--gameDir',str(out),'--assetsDir',str(prism/'assets'),'--assetIndex','34',
    '--uuid','00000000000000000000000000000001','--accessToken','0','--offlineDeveloperMode',
    '--versionType','release','--width',str(args.width),'--height',str(args.height)]
if not args.new_world and not args.loading_grid_test and (not args.ui or args.ui_world): command += ['--quickPlaySingleplayer','TriniumTest']
environment=os.environ.copy()
if system=='linux': environment['SDL_VIDEODRIVER']='x11' # Wayland forbids explicit window placement.
with (out/'client.log').open('w') as log:
    subprocess.run(command,cwd=out,env=environment,stdout=log,stderr=subprocess.STDOUT,timeout=300,check=True)
if args.climate_probe:
    climate=json.loads((out/'climate-distance-result.json').read_text())
    if climate.get('passed') is not True:raise SystemExit('Climate distance parity oracle failed')
if args.native_packet_gpu:
    native=json.loads((out/'native-packet-gpu.json').read_text())
    if native.get('passed') is not True:raise SystemExit('Native packet GPU oracle failed')
if args.culling_test:
    result=json.loads((out/'chest-culling-parity.json').read_text())
    print(json.dumps(result,indent=2))
    raise SystemExit(0 if result.get('passed') is True else 1)
if args.loading_grid_test:
    from PIL import Image,ImageChops
    result=json.loads((out/'loading-grid-result.json').read_text())
    result['pixelComparisons']=[]
    for scenario in range(8):
        a=Image.open(out/f'screenshots/loading-grid-{scenario}-reference.png').convert('RGBA')
        b=Image.open(out/f'screenshots/loading-grid-{scenario}-batch.png').convert('RGBA')
        delta=ImageChops.difference(a,b)
        changed=sum(any(pixel) for pixel in delta.get_flattened_data())
        result['pixelComparisons'].append(dict(scenario=scenario,changedPixels=changed,pixels=a.width*a.height))
    result['passed']=all(r['changedPixels']==0 for r in result['pixelComparisons'])
    (out/'loading-grid-result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    raise SystemExit(0 if result['passed'] else 1)
if args.standalone_shaders:
    result=json.loads((out/'standalone-result.json').read_text())
    from PIL import Image
    pixels=Image.open(out/'screenshots/standalone-pixels.png').convert('RGB')
    # Minecraft draws its vignette after world effects. Validate an unaffected
    # central patch, then the expected color ratios over the vignetted scene.
    patch=pixels.crop((pixels.width//2-16,pixels.height//2-48,pixels.width//2+16,pixels.height//2-16))
    expected=(32,128,191)
    matched=sum(all(abs(a-b)<=1 for a,b in zip(pixel,expected)) for pixel in patch.get_flattened_data())
    result['expectedColorFraction']=matched/(patch.width*patch.height)
    scene=pixels.crop((4,4,pixels.width-4,pixels.height//2))
    ratios=sum(0.3<=g/128<=1.05 and abs(r-32*g/128)<=2 and abs(b-191*g/128)<=2 for r,g,b in scene.get_flattened_data())
    result['vignetteColorFraction']=ratios/(scene.width*scene.height)
    result['failed']=result['failed'] or result['expectedColorFraction']<0.99 or result['vignetteColorFraction']<0.99
    (out/'standalone-result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    raise SystemExit(1 if result['failed'] else 0)
if args.ui:
    ui=json.loads((out/'ui-result.json').read_text())
    print(json.dumps(ui,indent=2))
    raise SystemExit(1 if ui['failed'] else 0)
result = json.loads((out/'test-result.json').read_text())
if args.reload:
    timings=json.loads((out/'timings.json').read_text())
    reloads=timings.get('resourceReloadSamplesMs',[])
    if len(reloads)!=args.reload_count or any(not value>0 for value in reloads):
        raise SystemExit('Requested resource reloads did not all complete')
if not result['frames']: raise SystemExit('No frames captured')
if result.get('iconified', True) or result.get('inactivityLimit') != 'minimized':
    raise SystemExit('The inactivity throttle could invalidate frame-time measurements')
if result['width'] != args.width or result['height'] != args.height or result['debugOverlay'] or result['vsync'] or result['renderDistance'] != args.render_distance or result['simulationDistance'] != args.simulation_distance:
    raise SystemExit('Comparison settings were not respected')
if not args.vanilla:
    diagnostic=json.loads((out/'config/trinium/diagnostic.json').read_text())
    if args.mode_off:
        disabled=json.loads((out/'mode-off-result.json').read_text())
        if not disabled['passed'] or diagnostic['failed'] or diagnostic['active'] or diagnostic['nativeAvailable'] or diagnostic['graphicsDraws'] or diagnostic['nativeSections'] or diagnostic['paletteReplaced']:
            raise SystemExit('Global OFF contract failed')
    elif diagnostic['failed'] or (not args.extra_mod and (not diagnostic['sectionHookObserved'] or not diagnostic['paletteHookObserved'])):
        raise SystemExit('Integration hooks did not run successfully')
    if args.java_fallback and (diagnostic['nativeAvailable'] or diagnostic['javaSections'] == 0):
        raise SystemExit('Java fallback was not observed')
    if not args.mode_off and not args.java_fallback and (not diagnostic['nativeAvailable'] or (not args.extra_mod and not diagnostic['nativeSortCalls'] and not diagnostic.get('nativeSections',0))):
        raise SystemExit('Native rendering-pipeline calls were not observed')
    if not args.mode_off and not args.java_fallback and not args.graphics_off and not args.jar and not args.extra_mod and diagnostic.get('graphicsDraws',0) == 0:
        raise SystemExit('Rust GPU commands were not executed')
if args.indirect_parity:
    from PIL import Image,ImageChops
    state=json.loads((out/'indirect-state.json').read_text())
    a=Image.open(out/'screenshots/indirect-naive-a.png').convert('RGB')
    b=Image.open(out/'screenshots/indirect-mdi-b.png').convert('RGB')
    c=Image.open(out/'screenshots/indirect-naive-c.png').convert('RGB')
    def changed(first,second):
        channels=ImageChops.difference(first,second).split()
        largest=ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2])
        hist=largest.histogram()
        return sum(hist[2:])/(first.width*first.height)
    noise=changed(a,c);difference=min(changed(a,b),changed(c,b))
    state.update({'naiveNoiseFraction':noise,'indirectDifferenceFraction':difference,'indirectDuringSample':result['multiDrawIndirect']})
    state['passed']=state['meshStable'] and noise<0.001 and difference<=noise*1.25+0.0001 and result['multiDrawIndirect']
    (out/'indirect-parity.json').write_text(json.dumps(state,indent=2)+'\n')
    print(json.dumps(state,indent=2))
    if not state['passed']:raise SystemExit('Indirect parity failed or was inconclusive; do not enable this workaround')
print(json.dumps(result,indent=2))
if args.direct_parity:
    state=json.loads((out/'direct-parity.json').read_text())
    if not state['passed']: raise SystemExit('Direct terrain parity failed')
print('Integration test completed. A single run does not establish an FPS improvement.')
