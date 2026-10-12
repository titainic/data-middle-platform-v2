package tech.qiantong.qdata.module.dp.api.warehouse;

public class ServingRequestException extends RuntimeException {
    private final int status;
    public ServingRequestException(int status,String message){super(message);this.status=status;}
    public int getStatus(){return status;}
}
