 
import pandas 
import numpy 
import pp

from orderreplay import *
import thrift

def get_shuffled_data(asset, proportion, windowSize, 
                variables = ['midPrice'], 
                server = 'cseesp1', port = 9090):
    from thrift.transport import TSocket
    from thrift.protocol import TBinaryProtocol
    from orderreplay import *
    transport = TSocket.TSocket(server, port)
    protocol = TBinaryProtocol.TBinaryProtocol(transport)
    client = OrderReplay.Client(protocol)
    transport.open()
    df = pandas.DataFrame(client.shuffledReplay(asset, variables, proportion, windowSize))
    if len(df) == 0:
        raise Exception("No data available for " + asset + " between " + \
                            start_date + " and " + end_date)
    event_time = range(df.shape[0])
    for variable in variables:
        df[variable].index = event_time
    return df
    
def perform_shuffle(proportion, window, directory='/var/data/orderflow-shuffle'):
    percentage = round(proportion * 100)
    dataset = get_shuffled_data('BHP', proportion, window)
    dataset.to_csv('%s/bhp-shuffled-ws%d-p%d.csv' % (directory, window, percentage))

job_server = pp.Server(ncpus=3, secret='shuffle') 

dep_modules = ('pandas', 'orderreplay', 'thrift')
dep_functions = (get_shuffled_data, )

jobs = []
for window in [4 ** (x + 1) for x in range(8)]:
    for proportion in numpy.arange(0, 1.1, 0.1):
        job = job_server.submit(perform_shuffle, (proportion, window), dep_functions, dep_modules)
        jobs.append(job)