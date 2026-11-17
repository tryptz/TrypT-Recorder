// SPDX-License-Identifier: GPL-3.0-or-later
// The snap-in factory, on its own so that a consumer can link the processors
// without DspEngine (dsp_engine.cpp), which only some consumers use.
#include "snapin_processor.h"
#include "snapins/gain.h"
#include "snapins/stereo.h"
#include "snapins/filter.h"
#include "snapins/eq_3band.h"
#include "snapins/compressor.h"
#include "snapins/limiter.h"
#include "snapins/gate.h"
#include "snapins/dynamics.h"
#include "snapins/compactor.h"
#include "snapins/transient_shaper.h"
#include "snapins/distortion.h"
#include "snapins/shaper.h"
#include "snapins/chorus.h"
#include "snapins/ensemble.h"
#include "snapins/flanger.h"
#include "snapins/phaser.h"
#include "snapins/delay.h"
#include "snapins/reverb.h"
#include "snapins/bitcrush.h"
#include "snapins/comb_filter.h"
#include "snapins/channel_mixer.h"
#include "snapins/formant_filter.h"
#include "snapins/frequency_shifter.h"
#include "snapins/haas.h"
#include "snapins/ladder_filter.h"
#include "snapins/nonlinear_filter.h"
#include "snapins/phase_distortion.h"
#include "snapins/pitch_shifter.h"
#include "snapins/resonator.h"
#include "snapins/reverser.h"
#include "snapins/ring_mod.h"
#include "snapins/tape_stop.h"
#include "snapins/trance_gate.h"
#include "snapins/eq_10band.h"
#include "snapins/disperser.h"
#include "snapins/misstortion.h"
#include "tac_log.h"

#define LOG_TAG "MonochromeDSP"
#define LOGE(...) TAC_LOG(ERROR, LOG_TAG, __VA_ARGS__)

SnapinProcessor* createSnapin(SnapinType type) {
    switch (type) {
        case SnapinType::GAIN:       return new GainProcessor();
        case SnapinType::STEREO:     return new StereoProcessor();
        case SnapinType::FILTER:     return new FilterProcessor();
        case SnapinType::EQ_3BAND:   return new Eq3BandProcessor();
        case SnapinType::COMPRESSOR: return new CompressorProcessor();
        case SnapinType::LIMITER:    return new LimiterProcessor();
        case SnapinType::GATE:       return new GateProcessor();
        case SnapinType::DYNAMICS:   return new DynamicsProcessor();
        case SnapinType::COMPACTOR:  return new CompactorProcessor();
        case SnapinType::TRANSIENT_SHAPER: return new TransientShaperProcessor();
        case SnapinType::DISTORTION: return new DistortionProcessor();
        case SnapinType::SHAPER:     return new ShaperProcessor();
        case SnapinType::CHORUS:     return new ChorusProcessor();
        case SnapinType::ENSEMBLE:   return new EnsembleProcessor();
        case SnapinType::FLANGER:    return new FlangerProcessor();
        case SnapinType::PHASER:     return new PhaserProcessor();
        case SnapinType::DELAY:      return new DelayProcessor();
        case SnapinType::REVERB:     return new ReverbProcessor();
        case SnapinType::BITCRUSH:   return new BitcrushProcessor();
        case SnapinType::COMB_FILTER: return new CombFilterProcessor();
        case SnapinType::CHANNEL_MIXER: return new ChannelMixerProcessor();
        case SnapinType::FORMANT_FILTER: return new FormantFilterProcessor();
        case SnapinType::FREQUENCY_SHIFTER: return new FrequencyShifterProcessor();
        case SnapinType::HAAS:       return new HaasProcessor();
        case SnapinType::LADDER_FILTER: return new LadderFilterProcessor();
        case SnapinType::NONLINEAR_FILTER: return new NonlinearFilterProcessor();
        case SnapinType::PHASE_DISTORTION: return new PhaseDistortionProcessor();
        case SnapinType::PITCH_SHIFTER: return new PitchShifterProcessor();
        case SnapinType::RESONATOR:  return new ResonatorProcessor();
        case SnapinType::REVERSER:   return new ReverserProcessor();
        case SnapinType::RING_MOD:   return new RingModProcessor();
        case SnapinType::TAPE_STOP:  return new TapeStopProcessor();
        case SnapinType::TRANCE_GATE: return new TranceGateProcessor();
        case SnapinType::EQ_10BAND:  return new Eq10BandProcessor();
        case SnapinType::DISPERSER:  return new DisperserProcessor();
        case SnapinType::MISSTORTION: return new MisstortionProcessor();
        default:
            LOGE("Unsupported snapin type: %d", static_cast<int>(type));
            return nullptr;
    }
}
