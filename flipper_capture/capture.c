#include <furi.h>
#include <furi_hal.h>
#include <furi_hal_infrared.h>
#include <gui/gui.h>
#include <input/input.h>
#include <rpc/rpc_app.h>
#include <storage/storage.h>
#include <flipper_format/flipper_format.h>
#include <flipper_format/flipper_format_i.h>
#include <toolbox/stream/stream.h>
#include <infrared/worker/infrared_worker.h>
#include <subghz/environment.h>
#include <subghz/receiver.h>
#include <subghz/subghz_worker.h>
#include <subghz/subghz_protocol_registry.h>
#include <subghz/devices/devices.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    RpcAppSystem* rpc;
    FuriMutex* rpc_lock;
    FuriMutex* signal_lock;
    FuriMessageQueue* commands;
    volatile bool quit;
    bool received;
    bool listening;
    bool radio_initialized;
    bool ir_mode;
    char token[37];
    char path[110];
    char protocol[64];
    InfraredWorker* ir_worker;
    InfraredMessage ir_message;
    uint32_t ir_timings[MAX_TIMINGS_AMOUNT];
    size_t ir_count;
    bool ir_raw;
    SubGhzEnvironment* environment;
    SubGhzReceiver* receiver;
    SubGhzWorker* worker;
    const SubGhzDevice* radio;
    SubGhzRadioPreset preset;
    FlipperFormat* decoded;
} Capture;

typedef struct { char text[200]; } Command;

static void reply(Capture* app, const char* type, const char* detail) {
    char text[280];
    snprintf(text, sizeof(text), "FH1\t%s\t%s\t%s\t%s", app->token, type, app->path, detail);
    furi_mutex_acquire(app->rpc_lock, FuriWaitForever);
    if(app->rpc) rpc_system_app_exchange_data(app->rpc, (uint8_t*)text, strlen(text));
    furi_mutex_release(app->rpc_lock);
}

static void rpc_callback(const RpcAppSystemEvent* event, void* context) {
    Capture* app = context;
    furi_mutex_acquire(app->rpc_lock, FuriWaitForever);
    if(event->type == RpcAppEventTypeSessionClose) {
        rpc_system_app_set_callback(app->rpc, NULL, NULL);
        app->rpc = NULL;
        app->quit = true;
    } else if(event->type == RpcAppEventTypeAppExit) {
        app->quit = true;
        rpc_system_app_confirm(app->rpc, true);
    } else if(event->type == RpcAppEventTypeDataExchange) {
        Command command = {0};
        bool valid = event->data.type == RpcAppSystemEventDataTypeBytes &&
                     event->data.bytes.size > 0 && event->data.bytes.size < sizeof(command.text) &&
                     !memchr(event->data.bytes.ptr, 0, event->data.bytes.size);
        if(valid) {
            memcpy(command.text, event->data.bytes.ptr, event->data.bytes.size);
            valid = furi_message_queue_put(app->commands, &command, 0) == FuriStatusOk;
        }
        rpc_system_app_confirm(app->rpc, valid);
    } else {
        rpc_system_app_confirm(app->rpc, false);
    }
    furi_mutex_release(app->rpc_lock);
}

static void draw(Canvas* canvas, void* context) {
    UNUSED(context);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 7, 18, "Flipper Home");
    canvas_set_font(canvas, FontSecondary);
    canvas_draw_str(canvas, 7, 35, "Capture from your phone");
    canvas_draw_str(canvas, 7, 51, "Back: cancel");
}

static void input(InputEvent* event, void* context) {
    if(event->key == InputKeyBack && event->type == InputTypeShort) ((Capture*)context)->quit = true;
}

static void ir_received(void* context, InfraredWorkerSignal* signal) {
    Capture* app = context;
    furi_mutex_acquire(app->signal_lock, FuriWaitForever);
    if(!app->received) {
        if(infrared_worker_signal_is_decoded(signal)) {
            const InfraredMessage* message = infrared_worker_get_decoded_signal(signal);
            app->ir_message = *message;
            app->ir_raw = false;
            app->received = true;
            snprintf(app->protocol, sizeof(app->protocol), "%s", infrared_get_protocol_name(message->protocol));
        } else {
            const uint32_t* timings;
            size_t count;
            infrared_worker_get_raw_signal(signal, &timings, &count);
            if(count > 0 && count <= MAX_TIMINGS_AMOUNT) {
                memcpy(app->ir_timings, timings, count * sizeof(uint32_t));
                app->ir_count = count;
                app->ir_raw = true;
                app->received = true;
                snprintf(app->protocol, sizeof(app->protocol), "Raw IR (38 kHz)");
            }
        }
    }
    furi_mutex_release(app->signal_lock);
}

static void decoded(SubGhzReceiver* receiver, SubGhzProtocolDecoderBase* base, void* context) {
    UNUSED(receiver);
    Capture* app = context;
    // Save only decoded, replayable fixed-code commands (e.g. Princeton lights).
    if(base->protocol->type != SubGhzProtocolTypeStatic ||
       !(base->protocol->flag & SubGhzProtocolFlag_Save) ||
       !(base->protocol->flag & SubGhzProtocolFlag_Send)) return;
    furi_mutex_acquire(app->signal_lock, FuriWaitForever);
    if(!app->received) {
        stream_clean(flipper_format_get_raw_stream(app->decoded));
        app->received = subghz_protocol_decoder_base_serialize(base, app->decoded, &app->preset) == SubGhzProtocolStatusOk;
        if(app->received) snprintf(app->protocol, sizeof(app->protocol), "%s", base->protocol->name);
    }
    furi_mutex_release(app->signal_lock);
}

static void pair(void* context, bool level, uint32_t duration) {
    Capture* app = context;
    subghz_receiver_decode(app->receiver, level, duration);
}

static void overrun(void* context) {
    subghz_receiver_reset(((Capture*)context)->receiver);
}

static bool uuid_valid(const char* text) {
    if(strlen(text) != 36) return false;
    for(size_t i = 0; i < 36; ++i) {
        if(i == 8 || i == 13 || i == 18 || i == 23) { if(text[i] != '-') return false; }
        else if(!((text[i] >= '0' && text[i] <= '9') || (text[i] >= 'a' && text[i] <= 'f'))) return false;
    }
    return true;
}

static void stop(Capture* app) {
    if(!app->listening) return;
    if(app->ir_mode) infrared_worker_rx_stop(app->ir_worker);
    else {
        subghz_devices_stop_async_rx(app->radio);
        subghz_worker_stop(app->worker);
        subghz_devices_sleep(app->radio);
    }
    app->listening = false;
}

static bool arm(Capture* app, const Command* command) {
    char mode[4], token[37], extra;
    unsigned long frequency;
    unsigned preset;
    // The path is derived locally from a UUID, never accepted from RPC input.
    if(sscanf(command->text, "FH1 ARM %36s %3s %lu %u %c", token, mode, &frequency, &preset, &extra) != 4 ||
       !uuid_valid(token) || (strcmp(mode, "IR") && strcmp(mode, "RF"))) return false;
    if(app->ir_worker || app->radio_initialized) return false; // One capture per launch.
    app->ir_mode = strcmp(mode, "IR") == 0;
    snprintf(app->token, sizeof(app->token), "%s", token);
    snprintf(app->path, sizeof(app->path), "/ext/%s/FlipperHome/%s.%s",
             app->ir_mode ? "infrared" : "subghz", token, app->ir_mode ? "ir" : "sub");
    if(app->ir_mode) {
        if(furi_hal_infrared_is_busy()) { reply(app, "ERROR", "Infrared receiver is busy"); return true; }
        app->ir_worker = infrared_worker_alloc();
        infrared_worker_rx_set_received_signal_callback(app->ir_worker, ir_received, app);
        infrared_worker_rx_enable_signal_decoding(app->ir_worker, true);
        infrared_worker_rx_start(app->ir_worker);
    } else {
        const FuriHalSubGhzPreset presets[] = {FuriHalSubGhzPresetOok650Async, FuriHalSubGhzPresetOok270Async,
                                             FuriHalSubGhzPreset2FSKDev238Async, FuriHalSubGhzPreset2FSKDev476Async};
        const char* names[] = {"AM650", "AM270", "FM238", "FM476"};
        subghz_devices_init();
        app->radio_initialized = true;
        app->radio = subghz_devices_get_by_name("cc1101_int");
        if(!app->radio || preset > 3 || frequency > UINT32_MAX ||
           !subghz_devices_is_frequency_valid(app->radio, frequency)) {
            reply(app, "ERROR", "Unsupported radio frequency or preset"); return true;
        }
        subghz_devices_begin(app->radio);
        subghz_devices_reset(app->radio);
        subghz_devices_idle(app->radio);
        subghz_devices_load_preset(app->radio, presets[preset], NULL);
        app->preset.frequency = subghz_devices_set_frequency(app->radio, frequency);
        app->preset.name = furi_string_alloc_set(names[preset]);
        app->environment = subghz_environment_alloc();
        subghz_environment_set_protocol_registry(app->environment, &subghz_protocol_registry);
        app->receiver = subghz_receiver_alloc_init(app->environment);
        subghz_receiver_set_filter(app->receiver, SubGhzProtocolFlag_Decodable);
        subghz_receiver_set_rx_callback(app->receiver, decoded, app);
        app->decoded = flipper_format_string_alloc();
        app->worker = subghz_worker_alloc();
        subghz_worker_set_context(app->worker, app);
        subghz_worker_set_pair_callback(app->worker, pair);
        subghz_worker_set_overrun_callback(app->worker, overrun);
        subghz_worker_start(app->worker);
        subghz_devices_start_async_rx(app->radio, subghz_worker_rx_callback, app->worker);
    }
    app->listening = true;
    reply(app, "READY", "Press the original remote button");
    return true;
}

static bool save(Capture* app) {
    Storage* storage = furi_record_open(RECORD_STORAGE);
    const char* root = app->ir_mode ? "/ext/infrared" : "/ext/subghz";
    storage_common_mkdir(storage, root);
    storage_common_mkdir(storage, app->ir_mode ? "/ext/infrared/FlipperHome" : "/ext/subghz/FlipperHome");
    bool success = false;
    if(app->ir_mode) {
        FlipperFormat* file = flipper_format_file_alloc(storage);
        success = flipper_format_file_open_new(file, app->path) &&
                  flipper_format_write_header_cstr(file, "IR signals file", 1) &&
                  flipper_format_write_string_cstr(file, "name", "Captured") &&
                  flipper_format_write_string_cstr(file, "type", app->ir_raw ? "raw" : "parsed");
        if(success && app->ir_raw) {
            uint32_t frequency = 38000;
            float duty = 0.33f;
            success = flipper_format_write_uint32(file, "frequency", &frequency, 1) &&
                      flipper_format_write_float(file, "duty_cycle", &duty, 1) &&
                      flipper_format_write_uint32(file, "data", app->ir_timings, app->ir_count);
        } else if(success) {
            success = flipper_format_write_string_cstr(file, "protocol", app->protocol) &&
                      flipper_format_write_hex(file, "address", (uint8_t*)&app->ir_message.address, 4) &&
                      flipper_format_write_hex(file, "command", (uint8_t*)&app->ir_message.command, 4);
        }
        flipper_format_file_close(file);
        flipper_format_free(file);
    } else {
        Stream* stream = flipper_format_get_raw_stream(app->decoded);
        size_t size = stream_size(stream);
        success = size > 0 && stream_rewind(stream) &&
                  stream_save_to_file(stream, storage, app->path, FSOM_CREATE_NEW) == size;
    }
    furi_record_close(RECORD_STORAGE);
    return success;
}

int32_t flipper_home_capture_app(void* args) {
    unsigned long rpc_address = 0;
    if(!args || sscanf(args, "RPC %lX", &rpc_address) != 1 || !rpc_address) return 0;
    Capture* app = calloc(1, sizeof(Capture));
    app->rpc = (RpcAppSystem*)rpc_address;
    app->rpc_lock = furi_mutex_alloc(FuriMutexTypeNormal);
    app->signal_lock = furi_mutex_alloc(FuriMutexTypeNormal);
    app->commands = furi_message_queue_alloc(2, sizeof(Command));
    Gui* gui = furi_record_open(RECORD_GUI);
    ViewPort* view = view_port_alloc();
    view_port_draw_callback_set(view, draw, app);
    view_port_input_callback_set(view, input, app);
    gui_add_view_port(gui, view, GuiLayerFullscreen);
    rpc_system_app_set_callback(app->rpc, rpc_callback, app);
    // SessionClose could arrive immediately after callback registration.
    furi_mutex_acquire(app->rpc_lock, FuriWaitForever);
    if(app->rpc) rpc_system_app_send_started(app->rpc);
    furi_mutex_release(app->rpc_lock);
    uint32_t deadline = furi_get_tick() + furi_ms_to_ticks(45000);
    while(!app->quit) {
        Command command;
        if(furi_message_queue_get(app->commands, &command, 50) == FuriStatusOk) {
            if(!arm(app, &command)) { reply(app, "ERROR", "Invalid capture request"); app->quit = true; }
            deadline = furi_get_tick() + furi_ms_to_ticks(30000);
        }
        furi_mutex_acquire(app->signal_lock, FuriWaitForever);
        bool received = app->received;
        furi_mutex_release(app->signal_lock);
        if(app->listening && received) {
            stop(app); // Join the workers before writing/freeing their copied signal.
            if(save(app)) reply(app, "CAPTURED", app->protocol);
            else reply(app, "ERROR", "Could not save signal. Check the SD card and try again.");
        }
        if((int32_t)(furi_get_tick() - deadline) >= 0) {
            stop(app);
            reply(app, "ERROR", "No replayable signal captured. Check frequency and try again.");
            app->quit = true;
        }
    }
    stop(app);
    furi_mutex_acquire(app->rpc_lock, FuriWaitForever);
    if(app->rpc) {
        rpc_system_app_send_exited(app->rpc);
        rpc_system_app_set_callback(app->rpc, NULL, NULL);
        app->rpc = NULL;
    }
    furi_mutex_release(app->rpc_lock);
    gui_remove_view_port(gui, view);
    view_port_free(view);
    furi_record_close(RECORD_GUI);
    if(app->ir_worker) infrared_worker_free(app->ir_worker);
    if(app->worker) subghz_worker_free(app->worker);
    if(app->receiver) subghz_receiver_free(app->receiver);
    if(app->environment) subghz_environment_free(app->environment);
    if(app->decoded) flipper_format_free(app->decoded);
    if(app->preset.name) furi_string_free(app->preset.name);
    if(app->radio_initialized) {
        if(app->radio) subghz_devices_end(app->radio);
        subghz_devices_deinit();
    }
    furi_message_queue_free(app->commands);
    furi_mutex_free(app->signal_lock);
    furi_mutex_free(app->rpc_lock);
    free(app);
    return 0;
}
