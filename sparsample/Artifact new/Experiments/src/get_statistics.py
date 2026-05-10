import pandas as pd
from utils import read_binary_message,load_context,load_model, get_bits_length_from_list
import time
from tqdm import tqdm
from datetime import datetime
import random
import torch
from utils import load_model_2
import numpy as np

def init_params():
    params_dict = {}
    params_dict['algo'] = 'sparsamp'
    params_dict['device'] = 'cuda:1'
    params_dict['top_p'] = 0.8
    params_dict['model_path'] = '/data/home/wls_panchao/dataroot/models/gpt2/'
    # params_dict['model_path'] = '/data/home/wls_pwl/PycharmProjects/MyPSS/MyPSS/llm/LLaMA3_8B/'
    # params_dict['model_path'] = '/data/home/wls_pwl/PycharmProjects/MyPSS/MyPSS/llm/Qwen2.5-3B-Instruct/'
    params_dict['min_token_length'] = 100  # Minimum token generation length
    params_dict['max_token_length'] = 200  # Maximum token generation length. Note: When using top-k or top-p sampling with small k/p values and large block_size, it's recommended to set a larger max_token_length or continue embedding into the next context
    params_dict['precision'] = 32
    params_dict['block_size'] = 62
    params_dict['seed'] = 777
    params_dict['baseline_flag'] = True # only for discop
    params_dict['reorder_flag'] = False # only for meteor
    return params_dict


def get_statistics(algo, top_p, block_size, seed, model_name):
    params_dict = init_params()
    params_dict['algo'] = algo
    params_dict['top_p'] = top_p
    params_dict['block_size'] = block_size
    params_dict['seed'] = seed

    if model_name == 'gpt2':
        params_dict['model_path'] = '/data/home/wls_panchao/dataroot/models/gpt2/'
    elif model_name == 'llama3':
        params_dict['model_path'] = '/data/home/wls_pwl/PycharmProjects/MyPSS/MyPSS/llm/LLaMA3_8B/'
    elif model_name == 'qwen':
        params_dict['model_path'] = '/data/home/wls_pwl/PycharmProjects/MyPSS/MyPSS/llm/Qwen2.5-3B-Instruct/'
    else:
        raise ValueError("Invalid model name")

    # Load contexts. The number of contexts used in one round of experiments can be adjusted as needed. Here we take 100.
    context_file = '../../context_data/imdb_context.xlsx'
    context_list = load_context(context_file)
    # context_list = np.array(context_list)[np.array([253, 253, 254, 254])].tolist()
    context_list = random.sample(context_list, 10) if len(context_list) > 100 else context_list

    # Load model and message file
    model, tokenizer = load_model_2(params_dict['model_path'], params_dict['device'])
    message = read_binary_message('../message.txt')
    message = message[19:]

    # Statistics initialization
    total_encoded_bits_length = 0
    total_generated_ids_length = 0
    total_decode_time = 0
    total_encode_time = 0
    total_model_time = 0
    total_entropy = 0
    correct_decoded = 0
    error_decoded = 0
    SE_different_num = 0
    context_num = len(context_list)
    # print(f"context_num: {context_num}")

    context_error_index = -1
    for context in tqdm(context_list, desc="Processing"):
        context = tokenizer.encode(context, return_tensors='pt').to(params_dict['device'])
        SE_diff = 0
        if params_dict['algo'] == 'sparsamp':
            from sparsamp import encode_spar, decode_spar
            try:
                t_1 = time.time()
                generated_ids, encoded_messages, total_entropy_cur_context, stat_time, model_time, Encode_SE_list = encode_spar(
                    model=model,
                    context=context,
                    message_bits=message,
                    min_token_length=params_dict['min_token_length'],
                    max_token_length=params_dict['max_token_length'],
                    device=params_dict['device'],
                    block_size=params_dict['block_size'],
                    top_p=params_dict['top_p'],
                    random_seed=params_dict['seed']
                    )
                t_2 = time.time()
                total_encode_time += t_2 - t_1 - stat_time
                total_model_time += model_time

                # torch.cuda.empty_cache()
                t_3 = time.time()
                reconstructed_message, SE_diff= decode_spar(
                    model=model,
                    generated_ids=generated_ids,
                    context=context,
                    enSE_list=Encode_SE_list,
                    device=params_dict['device'],
                    block_size=params_dict['block_size'],
                    top_p=params_dict['top_p'],
                    random_seed=params_dict['seed']
                    )
                t_4 = time.time()
                total_decode_time += t_4 - t_3
            except:
                print(f"The generated {params_dict['min_token_length']} to {params_dict['max_token_length']} tokens are insufficient to embed a message length that is an integer multiple of the {params_dict['block_size']}. Please switch to the next context. Note that this is not an embedding error!")
                context_num -= 1
                continue
            # context_error_index += 1

        else:
            print(f"not implement.")
            raise NotImplementedError

        # Calculate metrics - Summarize data obtained from current context
        SE_different_num += SE_diff
        encoded_bits_num = get_bits_length_from_list(encoded_messages)
        total_encoded_bits_length += encoded_bits_num
        total_entropy += total_entropy_cur_context
        total_generated_ids_length += len(generated_ids)
        if reconstructed_message == encoded_messages:
            correct_decoded += 1
        else:
            print(f"decoded error...")
            print(f"context_error_index:{context_error_index}")
            error_decoded += 1
            # if all(torch.equal(p1, p2) for p1, p2 in zip(Decode_probs_list, Encode_probs_list)):
            #     print(f"prob_list is same")
            # else:
            #     print(f"prob_list is different")
        
    cur_dict = {
        'algo': params_dict['algo'],
        'top_p': params_dict['top_p'],
        'model': params_dict['model_path'],
        'context_num': context_num,
        'correct_decoded':correct_decoded,
        'error_decode_num':error_decoded,
        'hardware_inconsistency_count': SE_different_num,
        'token_num': total_generated_ids_length,
        'bits_num': total_encoded_bits_length,
        'Embedding Rate': total_encoded_bits_length / total_generated_ids_length,
        # 'total_entropy': total_entropy,
        'Utilization': total_encoded_bits_length / total_entropy,
        'Embedding_Speed': total_encoded_bits_length / total_encode_time,
        'Decoding_Speed': total_encoded_bits_length / total_decode_time,
        'ATST': total_encode_time / total_generated_ids_length,
        'Generation_Speed': total_generated_ids_length / total_encode_time,
        'SITR': (total_encode_time - total_model_time) / total_encode_time,
        # 'total_encode_time': total_encode_time,
        # 'total_model_time': total_model_time,
        # 'total_decode_time': total_decode_time,
        'block_size (lm)': params_dict['block_size'],
        'Decoding Accuracy': correct_decoded / context_num, # Algorithm success rate (excluding hardware precision errors)
    }

    # print(f"cur_dict:{cur_dict}")

    return cur_dict


if __name__ == "__main__":
    # Parameter settings. For more parameter settings, see init_params().
    algo = 'sparsamp' # algo in ['adg','arithmetic', 'imec','meteor','sparsamp','discop']
    top_p = 1.0
    block_size = 64
    model_name = 'gpt2' # model_name in ['gpt2', 'llama3', 'qwen']
    seed = 777
    result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    print(f"Execution completed, results are as follows:")
    print(result_dict)

    # top_p = 0.8
    # block_size = 64
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 0.95
    # block_size = 64
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)

    # top_p = 1.0
    # block_size = 4
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 1.0
    # block_size = 8
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 1.0
    # block_size = 16
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 1.0
    # block_size = 32
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 1.0
    # block_size = 64
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 1.0
    # block_size = 128
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 1.0
    # block_size = 256
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 1.0
    # block_size = 512
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)
    #
    # top_p = 1.0
    # block_size = 1023
    # model_name = 'gpt2'  # model_name in ['gpt2', 'llama3', 'qwen']
    # seed = 777
    # result_dict = get_statistics(algo, top_p, block_size, seed, model_name)
    # print(f"Execution completed, results are as follows:")
    # print(result_dict)


